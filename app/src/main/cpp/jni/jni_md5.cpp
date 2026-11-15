#include "jni_common.h"
#include "md5.h"

#include <algorithm>
#include <fstream>
#include <set>
#include <sys/stat.h>
#include <unistd.h>
#include <dirent.h>
#include <fcntl.h>

extern "C" {



// --- md5 ------------------------------------------------------------------------

JNIEXPORT jbyteArray JNICALL
Java_com_utar_runner_core_NativeCore_md5(JNIEnv *env, jobject, jbyteArray data) {
	std::vector<uint8_t> bytes = jByteArrayToVector(env, data);
	uint8_t digest[16];
	utarcore::md5_buffer(bytes.empty() ? nullptr : bytes.data(), bytes.size(), digest);
	std::vector<uint8_t> out(digest, digest + 16);
	return vectorToJByteArray(env, out);
}
} // extern "C"
