#include "ink_story.h"

#include <godot_cpp/core/class_db.hpp>
#include <godot_cpp/variant/packed_byte_array.hpp>

#include <cstring>

using namespace godot;

void InkStory::_bind_methods() {
	ClassDB::bind_static_method("InkStory", D_METHOD("open", "file"), &InkStory::open);
}

InkStory::InkStory() {
}

InkStory::~InkStory() {
	// Drop the globals reference before destroying the story they belong to.
	globals = ink::runtime::globals(nullptr);
	if (story) {
		delete story;
		story = nullptr;
	}
}

Ref<InkStory> InkStory::open(const Ref<FileAccess> &p_file) {
	if (p_file.is_null()) {
		return Ref<InkStory>();
	}

	int64_t length = p_file->get_length();
	if (length <= 0) {
		return Ref<InkStory>();
	}

	p_file->seek(0);
	PackedByteArray data = p_file->get_buffer(length);
	if (data.size() != length) {
		return Ref<InkStory>();
	}

	unsigned char *buffer = new unsigned char[(size_t)length];
	memcpy(buffer, data.ptr(), (size_t)length);

	ink::runtime::story *ink_story = ink::runtime::story::from_binary(buffer, (size_t)length, true);
	if (!ink_story) {
		delete[] buffer;
		return Ref<InkStory>();
	}

	Ref<InkStory> result;
	result.instantiate();
	result->story = ink_story;
	result->globals = ink_story->new_globals();
	return result;
}
