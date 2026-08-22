#ifndef OTHELLO_EDAX_ANDROID_BRIDGE_H
#define OTHELLO_EDAX_ANDROID_BRIDGE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum {
    EDAX_ANDROID_OK = 0,
    EDAX_ANDROID_CANCELLED = 1,
    EDAX_ANDROID_INVALID_ARGUMENT = 2,
    EDAX_ANDROID_INVALID_EVAL = 3,
    EDAX_ANDROID_INVALID_BOOK = 4,
    EDAX_ANDROID_INTERNAL_ERROR = 5,
};

enum {
    EDAX_ANDROID_EXACT = 0,
    EDAX_ANDROID_HEURISTIC = 1,
    EDAX_ANDROID_BOOK = 2,
};

typedef struct EdaxAndroidMove {
    int square;
    int score;
    int kind;
    int depth;
    int selectivity_percent;
} EdaxAndroidMove;

typedef struct EdaxAndroidResult {
    int status;
    int count;
    char message[256];
    EdaxAndroidMove moves[33];
} EdaxAndroidResult;

typedef struct EdaxAndroidBestMoveResult {
    int status;
    int square;
    int from_book;
    char message[256];
} EdaxAndroidBestMoveResult;

int edax_android_validate_eval(const char *path, char *message, size_t message_size);
int edax_android_validate_book(const char *path, char *message, size_t message_size);
int edax_android_analyze(
    uint64_t player,
    uint64_t opponent,
    int side,
    int level,
    const char *eval_path,
    const char *book_path,
    int64_t request_id,
    EdaxAndroidResult *result
);
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
);
void edax_android_cancel(int64_t request_id);
const char *edax_android_version(void);

#ifdef __cplusplus
}
#endif

#endif
