#ifndef UTARCORE_WARMUP_H
#define UTARCORE_WARMUP_H

#include <string>

namespace utarcore {

/**
 * Hints the kernel to page the whole pack into cache before the engine touches it:
 * fadvise(SEQUENTIAL + WILLNEED) with a sequential-read fallback so every Android
 * version gets prefetching.
 */
bool warmupPack(const std::string &path);

} // namespace utarcore

#endif // UTARCORE_WARMUP_H
