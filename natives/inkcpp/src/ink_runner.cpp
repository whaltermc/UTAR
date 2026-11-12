#include "ink_runner.h"

#include <godot_cpp/core/class_db.hpp>

#include "choice.h"
#include "ink_bridge.h"

using namespace godot;

void InkRunner::_bind_methods() {
	ClassDB::bind_static_method("InkRunner", D_METHOD("from_ink_story", "story", "new_state"), &InkRunner::from_ink_story);

	ClassDB::bind_method(D_METHOD("get_global_tags"), &InkRunner::get_global_tags);
	ClassDB::bind_method(D_METHOD("get_tags"), &InkRunner::get_tags);
	ClassDB::bind_method(D_METHOD("get_tags_at_path", "path"), &InkRunner::get_tags_at_path);

	ClassDB::bind_method(D_METHOD("move_to_path", "path"), &InkRunner::move_to_path);

	ClassDB::bind_method(D_METHOD("can_continue"), &InkRunner::can_continue);
	ClassDB::bind_method(D_METHOD("advance"), &InkRunner::advance);
	ClassDB::bind_method(D_METHOD("get_choices"), &InkRunner::get_choices);
	ClassDB::bind_method(D_METHOD("choose_choice_index", "index"), &InkRunner::choose_choice_index);
	ClassDB::bind_method(D_METHOD("force_end"), &InkRunner::force_end);

	ClassDB::bind_method(D_METHOD("bind_external_function", "name", "callable"), &InkRunner::bind_external_function);

	ClassDB::bind_method(D_METHOD("get_ink_story"), &InkRunner::get_ink_story);
}

InkRunner::InkRunner() {
}

InkRunner::~InkRunner() {
	// Release the runner before the story reference is dropped.
	runner = ink::runtime::runner(nullptr);
}

Ref<InkRunner> InkRunner::from_ink_story(const Ref<InkStory> &p_story, bool p_new_state) {
	if (p_story.is_null() || p_story->get_story() == nullptr) {
		return Ref<InkRunner>();
	}

	Ref<InkRunner> result;
	result.instantiate();
	result->ink_story = p_story;

	if (p_new_state) {
		result->runner = p_story->get_story()->new_runner();
	} else {
		result->runner = p_story->get_story()->new_runner(p_story->get_globals());
	}

	if (!result->runner) {
		return Ref<InkRunner>();
	}

	return result;
}

Array InkRunner::_read_tags() {
	Array tags;
	if (!runner) {
		return tags;
	}

	size_t count = runner->num_tags();
	for (size_t i = 0; i < count; ++i) {
		const char *tag = runner->get_tag(i);
		if (tag) {
			tags.push_back(String::utf8(tag));
		}
	}
	return tags;
}

void InkRunner::_reset_priming() {
	has_primed_line = false;
	primed_once = false;
	primed_line = String();
	primed_tags.clear();
	current_tags.clear();
	global_tags.clear();
}

void InkRunner::_prime() {
	primed_once = true;
	if (forced_end || !runner || !runner->can_continue()) {
		return;
	}

	std::string line = runner->getline();
	primed_line = String::utf8(line.c_str());
	primed_tags = _read_tags();
	has_primed_line = true;
}

Array InkRunner::get_global_tags() {
	if (!primed_once) {
		_prime();
		global_tags = has_primed_line ? primed_tags : _read_tags();
	}
	return global_tags;
}

Array InkRunner::get_tags() {
	return current_tags;
}

Array InkRunner::get_tags_at_path(const String &p_path) {
	if (!runner) {
		return Array();
	}

	_reset_priming();
	CharString path = p_path.utf8();
	runner->move_to(ink::hash_string(path.get_data()));

	if (runner->can_continue()) {
		std::string line = runner->getline();
		primed_line = String::utf8(line.c_str());
		primed_tags = _read_tags();
		has_primed_line = true;
		primed_once = true;
		return primed_tags;
	}
	return _read_tags();
}

void InkRunner::move_to_path(const String &p_path) {
	if (!runner) {
		return;
	}
	_reset_priming();
	CharString path = p_path.utf8();
	runner->move_to(ink::hash_string(path.get_data()));
}

bool InkRunner::can_continue() {
	if (forced_end) {
		return false;
	}
	if (has_primed_line) {
		return true;
	}
	return runner && runner->can_continue();
}

String InkRunner::advance() {
	if (forced_end) {
		return String();
	}

	if (has_primed_line) {
		String line = primed_line;
		current_tags = primed_tags;
		has_primed_line = false;
		primed_line = String();
		return line;
	}

	if (!runner || !runner->can_continue()) {
		return String();
	}

	std::string line = runner->getline();
	current_tags = _read_tags();
	return String::utf8(line.c_str());
}

Array InkRunner::get_choices() {
	Array choices;
	if (forced_end || !runner) {
		return choices;
	}

	for (const ink::runtime::choice *c = runner->begin(); c != runner->end(); ++c) {
		const char *text = c->text();
		choices.push_back(String::utf8(text ? text : ""));
	}
	return choices;
}

void InkRunner::choose_choice_index(int p_index) {
	if (forced_end || !runner || p_index < 0) {
		return;
	}

	runner->choose((size_t)p_index);
	has_primed_line = false;
	primed_line = String();
	primed_tags.clear();
	current_tags.clear();
	primed_once = true;
}

void InkRunner::force_end() {
	forced_end = true;
	has_primed_line = false;
	primed_line = String();
	primed_once = true;
}

void InkRunner::bind_external_function(const String &p_name, const Callable &p_callable) {
	callables.push_back(p_callable);
	if (!runner) {
		return;
	}

	CharString name = p_name.utf8();
	runner->bind_raw(ink::hash_string(name.get_data()), new GdInkFunction(p_callable));
}

Ref<InkStory> InkRunner::get_ink_story() {
	return ink_story;
}
