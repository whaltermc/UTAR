#pragma once

#include <godot_cpp/classes/ref_counted.hpp>
#include <godot_cpp/templates/vector.hpp>
#include <godot_cpp/variant/array.hpp>
#include <godot_cpp/variant/callable.hpp>
#include <godot_cpp/variant/string.hpp>

#include "ink_story.h"
#include "runner.h"
#include "types.h"

namespace godot {

class InkRunner : public RefCounted {
	GDCLASS(InkRunner, RefCounted)

	// Kept alive for the runner's whole life: runner/globals are owned by the story.
	Ref<InkStory> ink_story;
	ink::runtime::runner runner;

	Vector<Callable> callables;

	Array global_tags;
	Array current_tags;
	Array primed_tags;
	String primed_line;
	bool has_primed_line = false;
	bool primed_once = false;
	bool forced_end = false;

	Array _read_tags();
	void _prime();
	void _reset_priming();

protected:
	static void _bind_methods();

public:
	static Ref<InkRunner> from_ink_story(const Ref<InkStory> &p_story, bool p_new_state);

	InkRunner();
	~InkRunner();

	Array get_global_tags();
	Array get_tags();
	Array get_tags_at_path(const String &p_path);

	void move_to_path(const String &p_path);

	bool can_continue();
	String advance();
	Array get_choices();
	void choose_choice_index(int p_index);
	void force_end();

	void bind_external_function(const String &p_name, const Callable &p_callable);

	Ref<InkStory> get_ink_story();
};

} // namespace godot
