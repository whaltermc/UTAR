#include "jni_common.h"
#include "pck_engine.h"

#include <algorithm>
#include <fstream>
#include <set>
#include <sys/stat.h>
#include <unistd.h>
#include <dirent.h>
#include <fcntl.h>

extern "C" {

// --- script dump ----------------------------------------------------------------

JNIEXPORT jint JNICALL
Java_com_utar_runner_core_NativeCore_scriptDump(JNIEnv *env, jobject, jlong handle, jstring logs_dir, jint max_chars) {
	g_lastError.clear();
	utarcore::PckFile *pck = reinterpret_cast<utarcore::PckFile *>(handle);
	if (pck == nullptr) {
		setError("Pack is not open.");
		return -1;
	}
	std::string dir = jstringToStd(env, logs_dir);
	mkdir(dir.c_str(), 0755);
	std::string dump_file = dir + "/scripts-dump.txt";

	// collect scripts/scenes
	std::vector<size_t> scripts;
	std::vector<size_t> scenes;
	for (size_t i = 0; i < pck->entryCount(); i++) {
		const utarcore::PckEntry &e = pck->entry(i);
		if (e.path.size() >= 3 && e.path.compare(e.path.size()-3, 3, ".gd") == 0) {
			if (!e.isEncrypted() && e.size >= 1 && e.size <= 512*1024) scripts.push_back(i);
		}
		if (e.path.size() >= 5 && e.path.compare(e.path.size()-5, 5, ".tscn") == 0) {
			scenes.push_back(i);
		}
	}
	// sort by path
	auto byPath = [&](size_t a, size_t b){ return pck->entry(a).path < pck->entry(b).path; };
	std::sort(scripts.begin(), scripts.end(), byPath);
	std::sort(scenes.begin(), scenes.end(), byPath);

	std::ostringstream out;
	out << "=== Script index (" << scripts.size() << " scripts) ===\n";
	for (size_t i : scripts) out << pck->entry(i).path << " (" << pck->entry(i).size << "B)\n";
	out << "\n=== Scene index (" << scenes.size() << " scenes; per top folder) ===\n";
	{
		std::map<std::string, int> perDir;
		for (size_t i : scenes) {
			const std::string &sp = pck->entry(i).path;
			size_t a = sp.find("res://scenes/");
			std::string key = "(other)";
			if (a == 0) {
				size_t b = sp.find('/', 13);
				key = sp.substr(13, b == std::string::npos ? std::string::npos : b - 13);
			}
			perDir[key]++;
		}
		for (const auto &kv : perDir) out << kv.first << ": " << kv.second << "\n";
	}
	out << "\n=== Sources ===\n";

	int sourceCount = 0;
	for (size_t i : scripts) {
		const utarcore::PckEntry &e = pck->entry(i);
		std::string path = e.path;
		std::string lower = path;
		for (char &c : lower) c = (char)std::tolower((unsigned char)c);
		if (lower.compare(0, 13, "res://addons/") == 0) continue;
		// Save / unlock / DLC / scene-select logic first; chat + dialogue scripts are no longer dumped
		// because they used up the whole size budget.
		bool interesting =
			lower.find("/store.gd") != std::string::npos ||
			lower.find("savegame") != std::string::npos ||
			lower.find("save_game") != std::string::npos ||
			lower.find("distplatform") != std::string::npos ||
			lower.find("sceneselect") != std::string::npos ||
			lower.find("/config") != std::string::npos ||
			lower.find("sceneview") != std::string::npos ||
			lower.find("screennavigator") != std::string::npos ||
			(lower.size() >= 7 && lower.compare(lower.size() - 7, 7, "/game.gd") == 0);
		if (!interesting) continue;
		std::vector<uint8_t> data;
		std::string error;
		if (!pck->readEntry(i, data, 512*1024, error)) continue;
		std::string source(data.begin(), data.end());
		out << "---- " << path << " ----\n";
		if ((int)source.size() > max_chars) {
			out << source.substr(source.size() - max_chars);
			out << "\n[... " << (source.size() - max_chars) << " earlier chars truncated ...]\n";
		} else {
			out << source << "\n";
		}
		out << "\n";
		sourceCount++;
	}

	std::ofstream f(dump_file, std::ios::trunc);
	if (!f) {
		setError("Couldn't write script dump.");
		return -1;
	}
	f << out.str();
	f.close();
	return sourceCount;
}
} // extern "C"
