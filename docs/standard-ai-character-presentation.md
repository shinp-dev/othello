# Standard opponent presentation

The current domain and distribution contract is documented in [Opponent Packs](opponent-packs.md).
Characters are Players inside an OpponentPack; neither UI nor progress uses a global level enum.

The packaged Animal Challenge retains its eight opponents and original AI tuning. Other packs use the
same selection, match, and result screens. Player IDs and explicit prerequisites control progression;
display order does not imply strength or unlock order.

Introductions are remembered per user, pack, and player. Result artwork uses the player's
`loseImage` when the human wins and `winImage` when the opponent wins or the match is drawn.
First clears display the newly unlocked player; an explicit MILESTONE unlock receives stronger
presentation. Completion means all current players in that pack have been defeated without Undo.

Presentation remains separate from persisted progression. Replay resets transient match effects,
not clear/unlock records. Downloaded image paths are validated pack-local files passed to Coil.
