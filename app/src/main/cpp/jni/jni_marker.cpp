#include "jni_common.h"

#include <algorithm>
#include <fstream>
#include <set>
#include <sys/stat.h>
#include <unistd.h>
#include <dirent.h>
#include <fcntl.h>

extern "C" {


// --- shader patch marker -------------------------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_com_utar_runner_core_NativeCore_markerWrite(JNIEnv *env, jobject, jstring files_dir, jstring value) {
	g_lastError.clear();
	std::string dir = jstringToStd(env, files_dir);
	std::string patch_dir = dir + "/utar_patches";
	mkdir(patch_dir.c_str(), 0755);
	std::string marker_path = patch_dir + "/backend.txt";
	int fd = open(marker_path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
	if (fd < 0) {
		setError("Couldn't write the shader patch marker.");
		return JNI_FALSE;
	}
	std::string content = jstringToStd(env, value);
	bool ok = true;
	size_t done = 0;
	while (done < content.size()) {
		ssize_t put = write(fd, content.data() + done, content.size() - done);
		if (put <= 0) {
			ok = false;
			break;
		}
		done += size_t(put);
	}
	close(fd);
	if (!ok) {
		setError("Couldn't write the shader patch marker.");
	}
	return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_utar_runner_core_NativeCore_patcherClear(JNIEnv *env, jobject, jstring files_dir) {
	g_lastError.clear();
	std::string dir = jstringToStd(env, files_dir);
	bool ok = true;
	const char *names[] = { "utar_patches", "shader_cache" };
	for (const char *name : names) {
		std::string path = dir + "/" + name;
		DIR *handle = opendir(path.c_str());
		if (handle == nullptr) {
			continue;
		}
		while (dirent *entry = readdir(handle)) {
			std::string child = entry->d_name;
			if (child == "." || child == "..") {
				continue;
			}
			if (unlink((path + "/" + child).c_str()) != 0) {
				ok = false;
			}
		}
		closedir(handle);
		if (rmdir(path.c_str()) != 0) {
			ok = false;
		}
	}
	if (!ok) {
		setError("Couldn't clear the shader patch data.");
	}
	return ok ? JNI_TRUE : JNI_FALSE;
}
} // extern "C"
