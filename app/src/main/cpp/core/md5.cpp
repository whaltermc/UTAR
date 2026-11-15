#include "md5.h"

#include <fcntl.h>
#include <unistd.h>

#include <algorithm>
#include <cstring>

namespace utarcore {

namespace {

struct MD5Context {
	uint32_t state[4];
	uint64_t bits;
	uint8_t buffer[64];
	size_t buffered;
};

void md5_init(MD5Context &ctx) {
	ctx.state[0] = 0x67452301;
	ctx.state[1] = 0xefcdab89;
	ctx.state[2] = 0x98badcfe;
	ctx.state[3] = 0x10325476;
	ctx.bits = 0;
	ctx.buffered = 0;
}

const uint32_t MD5_K[64] = {
	0xd76aa478, 0xe8c7b756, 0x242070db, 0xc1bdceee, 0xf57c0faf, 0x4787c62a, 0xa8304613, 0xfd469501,
	0x698098d8, 0x8b44f7af, 0xffff5bb1, 0x895cd7be, 0x6b901122, 0xfd987193, 0xa679438e, 0x49b40821,
	0xf61e2562, 0xc040b340, 0x265e5a51, 0xe9b6c7aa, 0xd62f105d, 0x02441453, 0xd8a1e681, 0xe7d3fbc8,
	0x21e1cde6, 0xc33707d6, 0xf4d50d87, 0x455a14ed, 0xa9e3e905, 0xfcefa3f8, 0x676f02d9, 0x8d2a4c8a,
	0xfffa3942, 0x8771f681, 0x6d9d6122, 0xfde5380c, 0xa4beea44, 0x4bdecfa9, 0xf6bb4b60, 0xbebfbc70,
	0x289b7ec6, 0xeaa127fa, 0xd4ef3085, 0x04881d05, 0xd9d4d039, 0xe6db99e5, 0x1fa27cf8, 0xc4ac5665,
	0xf4292244, 0x432aff97, 0xab9423a7, 0xfc93a039, 0x655b59c3, 0x8f0ccc92, 0xffeff47d, 0x85845dd1,
	0x6fa87e4f, 0xfe2ce6e0, 0xa3014314, 0x4e0811a1, 0xf7537e82, 0xbd3af235, 0x2ad7d2bb, 0xeb86d391,
};

const int MD5_S[64] = {
	7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
	5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
	4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
	6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
};

inline uint32_t md5_rotate(uint32_t v, int s) {
	return (v << s) | (v >> (32 - s));
}

void md5_block(MD5Context &ctx, const uint8_t *block) {
	uint32_t m[16];
	for (int i = 0; i < 16; i++) {
		m[i] = uint32_t(block[i * 4]) | (uint32_t(block[i * 4 + 1]) << 8) |
				(uint32_t(block[i * 4 + 2]) << 16) | (uint32_t(block[i * 4 + 3]) << 24);
	}
	uint32_t a = ctx.state[0], b = ctx.state[1], c = ctx.state[2], d = ctx.state[3];
	for (int i = 0; i < 64; i++) {
		uint32_t f;
		int g;
		if (i < 16) {
			f = (b & c) | (~b & d);
			g = i;
		} else if (i < 32) {
			f = (d & b) | (~d & c);
			g = (5 * i + 1) % 16;
		} else if (i < 48) {
			f = b ^ c ^ d;
			g = (3 * i + 5) % 16;
		} else {
			f = c ^ (b | ~d);
			g = (7 * i) % 16;
		}
		uint32_t temp = d;
		d = c;
		c = b;
		b = b + md5_rotate(a + f + MD5_K[i] + m[g], MD5_S[i]);
		a = temp;
	}
	ctx.state[0] += a;
	ctx.state[1] += b;
	ctx.state[2] += c;
	ctx.state[3] += d;
}

void md5_update(MD5Context &ctx, const uint8_t *data, size_t length) {
	ctx.bits += uint64_t(length) * 8;
	while (length > 0) {
		if (ctx.buffered == 0 && length >= 64) {
			md5_block(ctx, data);
			data += 64;
			length -= 64;
			continue;
		}
		size_t take = std::min(64 - ctx.buffered, length);
		memcpy(ctx.buffer + ctx.buffered, data, take);
		ctx.buffered += take;
		data += take;
		length -= take;
		if (ctx.buffered == 64) {
			md5_block(ctx, ctx.buffer);
			ctx.buffered = 0;
		}
	}
}

void md5_digest(MD5Context &ctx, uint8_t digest[16]) {
	uint64_t bits = ctx.bits;
	uint8_t pad = 0x80;
	md5_update(ctx, &pad, 1);
	uint8_t zeros[64] = { 0 };
	if (ctx.buffered > 56) {
		md5_update(ctx, zeros, 64 - ctx.buffered);
	}
	md5_update(ctx, zeros, 56 - ctx.buffered);
	uint8_t length_bytes[8];
	for (int i = 0; i < 8; i++) {
		length_bytes[i] = uint8_t((bits >> (8 * i)) & 0xFF);
	}
	md5_update(ctx, length_bytes, 8);
	for (int i = 0; i < 4; i++) {
		digest[i * 4 + 0] = uint8_t(ctx.state[i] & 0xFF);
		digest[i * 4 + 1] = uint8_t((ctx.state[i] >> 8) & 0xFF);
		digest[i * 4 + 2] = uint8_t((ctx.state[i] >> 16) & 0xFF);
		digest[i * 4 + 3] = uint8_t((ctx.state[i] >> 24) & 0xFF);
	}
}

} // namespace

void md5_buffer(const uint8_t *data, size_t length, uint8_t digest[16]) {
	MD5Context ctx;
	md5_init(ctx);
	md5_update(ctx, data, length);
	md5_digest(ctx, digest);
}

std::string md5_hex(const uint8_t digest[16]) {
	static const char *hex = "0123456789abcdef";
	std::string out;
	out.reserve(32);
	for (int i = 0; i < 16; i++) {
		out += hex[digest[i] >> 4];
		out += hex[digest[i] & 0x0F];
	}
	return out;
}

bool md5_file(const std::string &path, uint8_t digest[16]) {
	int fd = open(path.c_str(), O_RDONLY);
	if (fd < 0) {
		return false;
	}
	MD5Context ctx;
	md5_init(ctx);
	uint8_t buffer[1 << 16];
	while (true) {
		ssize_t got = read(fd, buffer, sizeof(buffer));
		if (got < 0) {
			close(fd);
			return false;
		}
		if (got == 0) {
			break;
		}
		md5_update(ctx, buffer, size_t(got));
	}
	close(fd);
	md5_digest(ctx, digest);
	return true;
}

} // namespace utarcore
