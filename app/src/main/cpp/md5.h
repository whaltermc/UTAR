#ifndef UTARCORE_MD5_H
#define UTARCORE_MD5_H

#include <cstddef>
#include <cstdint>
#include <string>

namespace utarcore {

/** RFC 1321 MD5, shared by the PCK writer (entry hashes) and the library cache keys. */
void md5_buffer(const uint8_t *data, size_t length, uint8_t digest[16]);

/** Convenience: digest formatted as 32 lowercase hex characters. */
std::string md5_hex(const uint8_t digest[16]);

/** Streams a file through the digest; false when the file cannot be read. */
bool md5_file(const std::string &path, uint8_t digest[16]);

} // namespace utarcore

#endif // UTARCORE_MD5_H
