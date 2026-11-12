#pragma once

#include <godot_cpp/classes/file_access.hpp>
#include <godot_cpp/classes/ref_counted.hpp>

#include "globals.h"
#include "story.h"
#include "types.h"

namespace godot {

class InkRunner;

class InkStory : public RefCounted {
	GDCLASS(InkStory, RefCounted)

	ink::runtime::story *story = nullptr;
	ink::runtime::globals globals;

protected:
	static void _bind_methods();

public:
	static Ref<InkStory> open(const Ref<FileAccess> &p_file);

	InkStory();
	~InkStory();

	ink::runtime::story *get_story() const { return story; }
	ink::runtime::globals get_globals() const { return globals; }
};

} // namespace godot
