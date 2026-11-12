#pragma once

#include <godot_cpp/variant/array.hpp>
#include <godot_cpp/variant/callable.hpp>
#include <godot_cpp/variant/variant.hpp>

#include "functional.h"
#include "stack.h"
#include "string_table.h"
#include "value.h"

namespace godot {

// Forwards an ink external function of arbitrary arity to a Godot Callable.
class GdInkFunction : public ink::runtime::internal::function_base {
	Callable callable;

public:
	explicit GdInkFunction(const Callable &p_callable)
		: callable(p_callable) {}

	void call(ink::runtime::internal::basic_eval_stack *stack, ink::size_t length,
			ink::runtime::internal::string_table &strings) override;
};

Variant ink_value_to_variant(const ink::runtime::internal::value &v);

} // namespace godot
