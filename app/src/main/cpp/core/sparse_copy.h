#ifndef UTARCORE_SPARSE_COPY_H
#define UTARCORE_SPARSE_COPY_H

#include <cstdint>
#include <string>

#ifndef SEEK_DATA
#define SEEK_DATA 3
#endif
#ifndef SEEK_HOLE
#define SEEK_HOLE 4
#endif

namespace utarcore {

/** Progress callback for sparse copies: bytes of the source range already handled. */
using SparseProgressFn = void (*)(int64_t processed, void *user);

/** True when [p_fd] is a regular file that has holes (allocated blocks < length). */
bool fileIsSparse(int p_fd);

/**
 * Copies [p_length] bytes from [p_src_offset] of [p_src_fd] to [p_dst_offset] of [p_dst_fd],
 * writing only the source's allocated extents. Hole runs are skipped, so the destination keeps
 * them as holes as well; the caller sizes the destination with ftruncate().
 *
 * When [p_source_sparse] is false or the filesystem does not report extents this degrades to a
 * plain sequential copy of the same range. [r_written] receives the bytes actually written.
 */
bool sparseCopyRange(
		int p_src_fd, int p_dst_fd,
		uint64_t p_src_offset, uint64_t p_dst_offset, uint64_t p_length,
		bool p_source_sparse, SparseProgressFn p_progress, void *p_user,
		uint64_t &r_written, std::string &r_error);

} // namespace utarcore

#endif // UTARCORE_SPARSE_COPY_H
