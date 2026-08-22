#include "edax_android_bridge.h"

#include "bit.h"
#include "board.h"
#include "book.h"
#include "const.h"
#include "eval.h"
#include "move.h"
#include "options.h"
#include "search.h"
#include "stats.h"

#include <pthread.h>
#include <setjmp.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

#define EDAX_EVAL_SIZE_BYTES 13952436LL
#define EDAX_BOOK_HEADER_BYTES 42LL
#define EDAX_BOOK_MIN_POSITION_BYTES 42LL
#define EDAX_BOOK_MAX_BYTES (256LL * 1024LL * 1024LL)
#define EDAX_REVIEW_MIN_TIME_PER_CANDIDATE_MS 500
#define EDAX_REVIEW_MAX_TIME_PER_CANDIDATE_MS 10000
#define EDAX_REVIEW_TIME_PER_CANDIDATE_STEP_MS 500
#define EDAX_AI_MIN_LEVEL 1
#define EDAX_AI_MAX_LEVEL 8
#define EDAX_AI_MIN_MOVE_TIME_MS 500
#define EDAX_AI_MAX_MOVE_TIME_MS 10000

static pthread_mutex_t engine_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t active_mutex = PTHREAD_MUTEX_INITIALIZER;
static _Thread_local jmp_buf *fatal_jump = NULL;
static _Thread_local char fatal_message[256];
static bool globals_ready = false;
static bool eval_loaded = false;
static char eval_path_loaded[1024];
static bool book_loaded = false;
static Book loaded_book;
static char book_path_loaded[1024];
static Search *active_search = NULL;
static int64_t active_request_id = 0;
static atomic_llong cancelled_request_id = 0;

static void initialize_globals(void);

static void set_message(char *target, size_t target_size, const char *message) {
    if (target == NULL || target_size == 0) return;
    snprintf(target, target_size, "%s", message == NULL ? "Unknown Edax error" : message);
}

void edax_android_fatal(const char *file, const char *function, int line, const char *format, ...) {
    va_list arguments;
    va_start(arguments, format);
    int prefix = snprintf(fatal_message, sizeof fatal_message, "%s:%d %s: ", file, line, function);
    if (prefix < 0) prefix = 0;
    if ((size_t) prefix >= sizeof fatal_message) prefix = (int) sizeof fatal_message - 1;
    vsnprintf(fatal_message + prefix, sizeof fatal_message - (size_t) prefix, format, arguments);
    va_end(arguments);
    if (fatal_jump != NULL) longjmp(*fatal_jump, 1);
    abort();
}

static int stat_regular_file(const char *path, long long *size, char *message, size_t message_size) {
    struct stat file_stat;
    if (path == NULL || path[0] == '\0') {
        set_message(message, message_size, "File path is empty");
        return 0;
    }
    if (stat(path, &file_stat) != 0 || !S_ISREG(file_stat.st_mode)) {
        set_message(message, message_size, "File does not exist or is not a regular file");
        return 0;
    }
    *size = (long long) file_stat.st_size;
    return 1;
}

int edax_android_validate_eval(const char *path, char *message, size_t message_size) {
    long long size = 0;
    if (!stat_regular_file(path, &size, message, message_size)) return EDAX_ANDROID_INVALID_EVAL;
    if (size != EDAX_EVAL_SIZE_BYTES) {
        set_message(message, message_size, "Unexpected Edax evaluation-data size");
        return EDAX_ANDROID_INVALID_EVAL;
    }
    FILE *file = fopen(path, "rb");
    if (file == NULL) {
        set_message(message, message_size, "Cannot open Edax evaluation data");
        return EDAX_ANDROID_INVALID_EVAL;
    }
    uint32_t first = 0, second = 0;
    int read = (int) fread(&first, sizeof first, 1, file) + (int) fread(&second, sizeof second, 1, file);
    fclose(file);
    if (read != 2 || !((first == EDAX && second == EVAL) || (first == XADE && second == LAVE))) {
        set_message(message, message_size, "Not an Edax evaluation-data file");
        return EDAX_ANDROID_INVALID_EVAL;
    }
    set_message(message, message_size, "");
    return EDAX_ANDROID_OK;
}

static int validate_book_header(const char *path, char *message, size_t message_size) {
    long long size = 0;
    if (!stat_regular_file(path, &size, message, message_size)) return EDAX_ANDROID_INVALID_BOOK;
    if (size < EDAX_BOOK_HEADER_BYTES || size > EDAX_BOOK_MAX_BYTES) {
        set_message(message, message_size, "Unexpected Edax opening-book size");
        return EDAX_ANDROID_INVALID_BOOK;
    }
    FILE *file = fopen(path, "rb");
    if (file == NULL) {
        set_message(message, message_size, "Cannot open Edax opening book");
        return EDAX_ANDROID_INVALID_BOOK;
    }
    uint32_t first = 0, second = 0;
    unsigned char version = 0, release = 0;
    int32_t declared_nodes = 0;
    int read = (int) fread(&first, sizeof first, 1, file) + (int) fread(&second, sizeof second, 1, file);
    read += (int) fread(&version, 1, 1, file) + (int) fread(&release, 1, 1, file);
    if (read == 4 && fseek(file, EDAX_BOOK_HEADER_BYTES - (long long) sizeof declared_nodes, SEEK_SET) == 0) {
        read += (int) fread(&declared_nodes, sizeof declared_nodes, 1, file);
    }
    fclose(file);
    if (read != 5 || first != EDAX || second != BOOK || version != VERSION) {
        set_message(message, message_size, "Not a compatible Edax opening book");
        return EDAX_ANDROID_INVALID_BOOK;
    }
    if (declared_nodes < 0 || (long long) declared_nodes > (size - EDAX_BOOK_HEADER_BYTES) / EDAX_BOOK_MIN_POSITION_BYTES) {
        set_message(message, message_size, "Invalid Edax opening-book node count");
        return EDAX_ANDROID_INVALID_BOOK;
    }
    set_message(message, message_size, "");
    return EDAX_ANDROID_OK;
}

static void initialize_globals(void) {
    if (globals_ready) return;
    options.hash_table_size = 14;
    options.n_task = 1;
    options.verbosity = 0;
    options.info = false;
    options.noise = 99;
    edge_stability_init();
    statistics_init();
    search_global_init();
    globals_ready = true;
}

int edax_android_validate_book(const char *path, char *message, size_t message_size) {
    int validation = validate_book_header(path, message, message_size);
    if (validation != EDAX_ANDROID_OK) return validation;
    pthread_mutex_lock(&engine_mutex);
    jmp_buf jump;
    fatal_jump = &jump;
    if (setjmp(jump) != 0) {
        set_message(message, message_size, fatal_message);
        fatal_jump = NULL;
        pthread_mutex_unlock(&engine_mutex);
        return EDAX_ANDROID_INVALID_BOOK;
    }
    initialize_globals();
    Book probe;
    memset(&probe, 0, sizeof probe);
    bool loaded = book_load(&probe, path);
    book_free(&probe);
    fatal_jump = NULL;
    pthread_mutex_unlock(&engine_mutex);
    if (!loaded) {
        set_message(message, message_size, "Edax could not load the opening book");
        return EDAX_ANDROID_INVALID_BOOK;
    }
    set_message(message, message_size, "");
    return EDAX_ANDROID_OK;
}

static int ensure_eval(const char *path, char *message, size_t message_size) {
    int validation = edax_android_validate_eval(path, message, message_size);
    if (validation != EDAX_ANDROID_OK) return validation;
    if (eval_loaded && strcmp(eval_path_loaded, path) == 0) return EDAX_ANDROID_OK;
    if (eval_loaded) {
        eval_close();
        eval_loaded = false;
        eval_path_loaded[0] = '\0';
    }
    eval_open(path);
    eval_loaded = true;
    snprintf(eval_path_loaded, sizeof eval_path_loaded, "%s", path);
    return EDAX_ANDROID_OK;
}

static int ensure_book(const char *path, char *message, size_t message_size) {
    if (path == NULL || path[0] == '\0') {
        if (book_loaded) {
            book_free(&loaded_book);
            book_loaded = false;
            book_path_loaded[0] = '\0';
        }
        return EDAX_ANDROID_OK;
    }
    int validation = validate_book_header(path, message, message_size);
    if (validation != EDAX_ANDROID_OK) return validation;
    if (book_loaded && strcmp(book_path_loaded, path) == 0) return EDAX_ANDROID_OK;
    if (book_loaded) {
        book_free(&loaded_book);
        book_loaded = false;
        book_path_loaded[0] = '\0';
    }
    memset(&loaded_book, 0, sizeof loaded_book);
    if (!book_load(&loaded_book, path)) {
        book_free(&loaded_book);
        set_message(message, message_size, "Edax could not load the opening book");
        return EDAX_ANDROID_INVALID_BOOK;
    }
    book_loaded = true;
    snprintf(book_path_loaded, sizeof book_path_loaded, "%s", path);
    return EDAX_ANDROID_OK;
}

static bool is_cancelled(int64_t request_id) {
    return atomic_load(&cancelled_request_id) == request_id;
}

static void set_active_search(Search *search, int64_t request_id) {
    pthread_mutex_lock(&active_mutex);
    active_search = search;
    active_request_id = request_id;
    pthread_mutex_unlock(&active_mutex);
}

static void clear_active_search(Search *search) {
    pthread_mutex_lock(&active_mutex);
    if (active_search == search) {
        active_search = NULL;
        active_request_id = 0;
    }
    pthread_mutex_unlock(&active_mutex);
}

void edax_android_cancel(int64_t request_id) {
    atomic_store(&cancelled_request_id, request_id);
    pthread_mutex_lock(&active_mutex);
    if (active_search != NULL && active_request_id == request_id) {
        search_stop_all(active_search, STOP_ON_DEMAND);
    }
    pthread_mutex_unlock(&active_mutex);
}

static int selectivity_percent(int selectivity) {
    if (selectivity < 0 || selectivity > NO_SELECTIVITY) return 100;
    return selectivity_table[selectivity].percent;
}

int edax_android_analyze(
    uint64_t player,
    uint64_t opponent,
    int side,
    int level,
    int time_per_candidate_ms,
    const char *eval_path,
    const char *book_path,
    int64_t request_id,
    EdaxAndroidResult *result
) {
    if (result == NULL) return EDAX_ANDROID_INVALID_ARGUMENT;
    memset(result, 0, sizeof *result);
    if ((player & opponent) != 0 || (player | opponent) == 0 ||
        (side != BLACK && side != WHITE) || level < 1 || level > 18 ||
        time_per_candidate_ms < EDAX_REVIEW_MIN_TIME_PER_CANDIDATE_MS ||
        time_per_candidate_ms > EDAX_REVIEW_MAX_TIME_PER_CANDIDATE_MS ||
        (time_per_candidate_ms - EDAX_REVIEW_MIN_TIME_PER_CANDIDATE_MS) %
            EDAX_REVIEW_TIME_PER_CANDIDATE_STEP_MS != 0) {
        result->status = EDAX_ANDROID_INVALID_ARGUMENT;
        set_message(result->message, sizeof result->message, "Invalid board, side, Edax level, or candidate time");
        return result->status;
    }

    pthread_mutex_lock(&engine_mutex);
    if (is_cancelled(request_id)) {
        result->status = EDAX_ANDROID_CANCELLED;
        set_message(result->message, sizeof result->message, "Analysis cancelled");
        pthread_mutex_unlock(&engine_mutex);
        return result->status;
    }
    jmp_buf jump;
    fatal_jump = &jump;
    if (setjmp(jump) != 0) {
        result->status = EDAX_ANDROID_INTERNAL_ERROR;
        set_message(result->message, sizeof result->message, fatal_message);
        pthread_mutex_lock(&active_mutex);
        active_search = NULL;
        active_request_id = 0;
        pthread_mutex_unlock(&active_mutex);
        fatal_jump = NULL;
        pthread_mutex_unlock(&engine_mutex);
        return result->status;
    }

    initialize_globals();
    int status = ensure_eval(eval_path, result->message, sizeof result->message);
    if (status == EDAX_ANDROID_OK) status = ensure_book(book_path, result->message, sizeof result->message);
    if (status != EDAX_ANDROID_OK) {
        result->status = status;
        fatal_jump = NULL;
        pthread_mutex_unlock(&engine_mutex);
        return status;
    }

    Board root = {player, opponent};
    uint64_t legal = board_get_moves(&root);
    MoveList book_moves;
    bool has_book_position = book_loaded && book_get_moves(&loaded_book, &root, &book_moves);

    int square;
    foreach_bit(square, legal) {
        if (result->count >= 33) break;
        if (is_cancelled(request_id)) {
            result->status = EDAX_ANDROID_CANCELLED;
            set_message(result->message, sizeof result->message, "Analysis cancelled");
            break;
        }

        EdaxAndroidMove *output = &result->moves[result->count];
        output->square = square;
        bool book_hit = false;
        if (has_book_position) {
            Move *book_move;
            foreach_move(book_move, &book_moves) {
                if (book_move->x == square) {
                    output->score = book_move->score;
                    output->kind = EDAX_ANDROID_BOOK;
                    output->depth = -1;
                    output->selectivity_percent = 100;
                    book_hit = true;
                    break;
                }
            }
        }

        if (!book_hit) {
            Board child;
            if (board_next(&root, square, &child) == 0) {
                result->status = EDAX_ANDROID_INTERNAL_ERROR;
                set_message(result->message, sizeof result->message, "Edax produced an invalid legal move");
                break;
            }
            Search search;
            search_init(&search);
            search_set_board(&search, &child, side == BLACK ? WHITE : BLACK);
            search_set_level(&search, level, search.n_empties);
            search_set_move_time(&search, time_per_candidate_ms);
            set_active_search(&search, request_id);
            search_run(&search);
            clear_active_search(&search);
            /* STOP_TIMEOUT is a valid completed candidate result; only an on-demand stop cancels. */
            if (is_cancelled(request_id) || search.stop == STOP_ON_DEMAND) {
                search_free(&search);
                result->status = EDAX_ANDROID_CANCELLED;
                set_message(result->message, sizeof result->message, "Analysis cancelled");
                break;
            }
            output->score = -search.result->score;
            output->depth = search.result->depth;
            output->selectivity_percent = selectivity_percent(search.result->selectivity);
            output->kind = search.result->depth >= search.n_empties && search.result->selectivity == NO_SELECTIVITY
                ? EDAX_ANDROID_EXACT
                : EDAX_ANDROID_HEURISTIC;
            search_free(&search);
        }
        result->count++;
    }

    if (result->status == EDAX_ANDROID_OK) set_message(result->message, sizeof result->message, "");
    fatal_jump = NULL;
    pthread_mutex_unlock(&engine_mutex);
    return result->status;
}

static bool select_best_legal_book_move(
    const Board *root,
    uint64_t legal,
    int *best_square
) {
    MoveList book_moves;
    if (!book_loaded || !book_get_moves(&loaded_book, root, &book_moves)) return false;

    int square;
    int best_score = -SCORE_INF;
    bool found = false;
    foreach_bit(square, legal) {
        Move *book_move;
        foreach_move(book_move, &book_moves) {
            if (book_move->x == square) {
                if (!found || book_move->score > best_score) {
                    *best_square = square;
                    best_score = book_move->score;
                    found = true;
                }
                break;
            }
        }
    }
    return found;
}

int edax_android_choose_best_move(
    uint64_t player,
    uint64_t opponent,
    int side,
    int level,
    int move_time_ms,
    const char *eval_path,
    const char *book_path,
    int64_t request_id,
    EdaxAndroidBestMoveResult *result
) {
    if (result == NULL) return EDAX_ANDROID_INVALID_ARGUMENT;
    memset(result, 0, sizeof *result);
    result->square = NOMOVE;
    if ((player & opponent) != 0 || (player | opponent) == 0 ||
        (side != BLACK && side != WHITE) ||
        level < EDAX_AI_MIN_LEVEL || level > EDAX_AI_MAX_LEVEL ||
        move_time_ms < EDAX_AI_MIN_MOVE_TIME_MS || move_time_ms > EDAX_AI_MAX_MOVE_TIME_MS) {
        result->status = EDAX_ANDROID_INVALID_ARGUMENT;
        set_message(result->message, sizeof result->message, "Invalid board, side, AI level, or move time");
        return result->status;
    }

    Search * volatile search = NULL;
    volatile bool search_initialized = false;
    pthread_mutex_lock(&engine_mutex);
    if (is_cancelled(request_id)) {
        result->status = EDAX_ANDROID_CANCELLED;
        set_message(result->message, sizeof result->message, "AI move cancelled");
        pthread_mutex_unlock(&engine_mutex);
        return result->status;
    }

    jmp_buf jump;
    fatal_jump = &jump;
    if (setjmp(jump) != 0) {
        result->status = EDAX_ANDROID_INTERNAL_ERROR;
        set_message(result->message, sizeof result->message, fatal_message);
        if (search != NULL) {
            clear_active_search((Search *) search);
            if (search_initialized) search_free((Search *) search);
            free((Search *) search);
        }
        fatal_jump = NULL;
        pthread_mutex_unlock(&engine_mutex);
        return result->status;
    }

    initialize_globals();
    int status = ensure_eval(eval_path, result->message, sizeof result->message);
    if (status == EDAX_ANDROID_OK) status = ensure_book(book_path, result->message, sizeof result->message);
    if (status != EDAX_ANDROID_OK) {
        result->status = status;
        goto cleanup;
    }

    Board root = {player, opponent};
    uint64_t legal = board_get_moves(&root);
    if (legal == 0) {
        result->status = EDAX_ANDROID_INVALID_ARGUMENT;
        set_message(result->message, sizeof result->message, "AI position has no legal move");
        goto cleanup;
    }

    int book_square = NOMOVE;
    /* Preserve the former Kotlin max-score choice for legal book moves. */
    if (select_best_legal_book_move(&root, legal, &book_square)) {
        if (is_cancelled(request_id)) {
            result->status = EDAX_ANDROID_CANCELLED;
            set_message(result->message, sizeof result->message, "AI move cancelled");
        } else {
            result->square = book_square;
            result->from_book = 1;
        }
        goto cleanup;
    }

    search = (Search *) calloc(1, sizeof(Search));
    if (search == NULL) {
        result->status = EDAX_ANDROID_INTERNAL_ERROR;
        set_message(result->message, sizeof result->message, "Cannot allocate Edax AI search");
        goto cleanup;
    }
    search_init((Search *) search);
    search_initialized = true;
    search_set_board((Search *) search, &root, side);
    search_set_level((Search *) search, level, ((Search *) search)->n_empties);
    search_set_move_time((Search *) search, move_time_ms);
    set_active_search((Search *) search, request_id);
    if (is_cancelled(request_id)) {
        result->status = EDAX_ANDROID_CANCELLED;
        set_message(result->message, sizeof result->message, "AI move cancelled");
        goto cleanup;
    }

    search_run((Search *) search);
    clear_active_search((Search *) search);
    /* STOP_TIMEOUT is Edax's normal time-management result; only an explicit stop cancels. */
    if (is_cancelled(request_id) || ((Search *) search)->stop == STOP_ON_DEMAND) {
        result->status = EDAX_ANDROID_CANCELLED;
        set_message(result->message, sizeof result->message, "AI move cancelled");
        goto cleanup;
    }

    int square = ((Search *) search)->result->move;
    if (square < A1 || square > H8 || (legal & (UINT64_C(1) << square)) == 0) {
        result->status = EDAX_ANDROID_INTERNAL_ERROR;
        set_message(result->message, sizeof result->message, "Edax returned no legal AI move");
        goto cleanup;
    }
    result->square = square;

cleanup:
    if (search != NULL) {
        clear_active_search((Search *) search);
        if (search_initialized) search_free((Search *) search);
        free((Search *) search);
    }
    if (result->status == EDAX_ANDROID_OK) set_message(result->message, sizeof result->message, "");
    fatal_jump = NULL;
    pthread_mutex_unlock(&engine_mutex);
    return result->status;
}

const char *edax_android_version(void) {
    return EDAX_NAME " (upstream 14f048c05ddfa385b6bf954a9c2905bbe677e9d3)";
}
