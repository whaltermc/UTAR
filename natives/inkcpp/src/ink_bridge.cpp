#include "ink_bridge.h"

#include <godot_cpp/variant/string.hpp>

#include <cstring>
#include <vector>

using namespace godot;
using namespace ink::runtime::internal;

Variant godot::ink_value_to_variant(const value &v) {
	switch (v.type()) {
		case value_type::boolean:
			return v.get<value_type::boolean>();
		case value_type::int32:
			return v.get<value_type::int32>();
		case value_type::uint32:
			return (int64_t)v.get<value_type::uint32>();
		case value_type::float32:
			return v.get<value_type::float32>();
		case value_type::string:
			return String::utf8(v.get<value_type::string>().str);
		default:
			return Variant();
	}
}

void GdInkFunction::call(basic_eval_stack *stack, ink::size_t length, string_table &strings) {
	// inkcpp pushes arguments in order; popping yields the last one first.
	std::vector<value> vals(length);
	for (ink::size_t i = length; i > 0; --i) {
		vals[i - 1] = stack->pop();
	}

	Array args;
	for (const value &v : vals) {
		args.push_back(ink_value_to_variant(v));
	}

	Variant result;
	if (callable.is_valid()) {
		result = callable.callv(args);
	}

	switch (result.get_type()) {
		case Variant::BOOL:
			stack->push(value{}.set<value_type::boolean>((bool)result));
			break;
		case Variant::INT:
			stack->push(value{}.set<value_type::int32>((int32_t)(int64_t)result));
			break;
		case Variant::FLOAT:
			stack->push(value{}.set<value_type::float32>((float)(double)result));
			break;
		case Variant::STRING:
		case Variant::STRING_NAME: {
			String s = result;
			CharString utf8 = s.utf8();
			size_t len = (size_t)utf8.length();
			char *buf = allocate(strings, len + 1);
			memcpy(buf, utf8.get_data(), len);
			buf[len] = 0;
			push_string(stack, buf);
		} break;
		case Variant::NIL:
			stack->push(value{}.set<value_type::int32>(0));
			break;
		default:
			stack->push(value{}.set<value_type::int32>(0));
			break;
	}
}
