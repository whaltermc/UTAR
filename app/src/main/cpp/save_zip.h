#ifndef UTARCORE_SAVE_ZIP_H
#define UTARCORE_SAVE_ZIP_H

#include <cstdint>
#include <string>

namespace utarcore {

/** Progress callback: (done, total) — bytes for backup, file counts for restore. */
using SaveZipProgress = void (*)(int64_t done, int64_t total, void *user);

/**
 * Streams a zip archive of the game's user:// data (the app filesDir) to [p_fd], excluding
 * runner-owned paths (the staged pack, logs, the regenerable shader cache). Returns the
 * bytes written, or -1 with [r_error] on failure.
 */
int64_t saveBackupZip(const std::string &p_root_dir, int p_fd, SaveZipProgress p_progress, void *p_user,
		std::string &r_error);

/**
 * Replaces the current save data with the zip archive on [p_fd]. The archive is fully
 * scanned and every entry path validated before anything on disk is touched, so a bad or
 * hostile file fails without deleting the existing saves. Returns the number of files
 * restored, or -1 with [r_error] on failure.
 */
int64_t saveRestoreZip(int p_fd, const std::string &p_root_dir, SaveZipProgress p_progress, void *p_user,
		std::string &r_error);

} // namespace utarcore

#endif // UTARCORE_SAVE_ZIP_H
