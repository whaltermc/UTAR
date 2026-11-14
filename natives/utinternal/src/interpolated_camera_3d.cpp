#include "interpolated_camera_3d.h"

#include <godot_cpp/classes/engine.hpp>
#include <godot_cpp/core/class_db.hpp>
#include <godot_cpp/core/math.hpp>
#include <godot_cpp/variant/utility_functions.hpp>

using namespace godot;

InterpolatedCamera3D::InterpolatedCamera3D() {
	enabled = false;
	speed = 1.0;
	interpolation_mode = PROCESS_IDLE;
}

InterpolatedCamera3D::~InterpolatedCamera3D() {
}

void InterpolatedCamera3D::_notification(int p_what) {
	switch (p_what) {
		case NOTIFICATION_ENTER_TREE: {
			_update_process_mode();
		} break;

		case NOTIFICATION_INTERNAL_PROCESS: {
			if (interpolation_mode == PROCESS_PHYSICS) {
				break;
			}
			if (enabled) {
				_process_interpolation(speed * get_process_delta_time());
			}
		} break;

		case NOTIFICATION_INTERNAL_PHYSICS_PROCESS: {
			if (interpolation_mode == PROCESS_IDLE) {
				break;
			}
			if (enabled) {
				_process_interpolation(speed * get_physics_process_delta_time());
			}
		} break;
	}
}

void InterpolatedCamera3D::_process_interpolation(real_t p_delta) {
	Node3D *node = target.is_empty() ? nullptr : Object::cast_to<Node3D>(get_node_or_null(target));
	if (!node) {
		// One-shot diagnostic: a camera that is enabled but cannot resolve its
		// target never moves, which looks like a black/empty 3D scene.
		if (!warned_missing_target) {
			warned_missing_target = true;
			UtilityFunctions::print("InterpolatedCamera3D: target '", String(target), "' not found for ", String(get_path()));
		}
		return;
	}
	warned_missing_target = false;

	Transform3D target_xform = node->get_global_transform();
	Transform3D local_transform = get_global_transform();
	local_transform = local_transform.interpolate_with(target_xform, p_delta);
	set_global_transform(local_transform);

	Camera3D *cam = Object::cast_to<Camera3D>(node);
	if (cam && cam->get_projection() == get_projection()) {
		real_t new_near = Math::lerp((real_t)get_near(), (real_t)cam->get_near(), p_delta);
		real_t new_far = Math::lerp((real_t)get_far(), (real_t)cam->get_far(), p_delta);

		if (cam->get_projection() == PROJECTION_ORTHOGONAL) {
			real_t size = Math::lerp((real_t)get_size(), (real_t)cam->get_size(), p_delta);
			set_orthogonal(size, new_near, new_far);
		} else {
			real_t fov = Math::lerp((real_t)get_fov(), (real_t)cam->get_fov(), p_delta);
			set_perspective(fov, new_near, new_far);
		}
	}
}

void InterpolatedCamera3D::set_target_path(const NodePath &p_path) {
	target = p_path;
}

NodePath InterpolatedCamera3D::get_target_path() const {
	return target;
}

void InterpolatedCamera3D::set_target(Node3D *p_target) {
	ERR_FAIL_NULL(p_target);
	target = get_path_to(p_target);
}

void InterpolatedCamera3D::set_speed(real_t p_speed) {
	speed = p_speed;
}

real_t InterpolatedCamera3D::get_speed() const {
	return speed;
}

void InterpolatedCamera3D::set_interpolation_enabled(bool p_enable) {
	if (enabled == p_enable) {
		return;
	}
	enabled = p_enable;
	_update_process_mode();
}

bool InterpolatedCamera3D::is_interpolation_enabled() const {
	return enabled;
}

void InterpolatedCamera3D::set_interpolation_process_mode(InterpolatedCamera3D::InterpolationProcessMode p_mode) {
	if (interpolation_mode == p_mode) {
		return;
	}
	interpolation_mode = p_mode;
	_update_process_mode();
}

InterpolatedCamera3D::InterpolationProcessMode InterpolatedCamera3D::get_interpolation_process_mode() const {
	return interpolation_mode;
}

void InterpolatedCamera3D::_update_process_mode() {
	if (Engine::get_singleton()->is_editor_hint() || !enabled) {
		set_process_internal(false);
		set_physics_process_internal(false);
	} else if (interpolation_mode == PROCESS_IDLE) {
		set_process_internal(true);
		set_physics_process_internal(false);
	} else {
		set_process_internal(false);
		set_physics_process_internal(true);
	}
}

void InterpolatedCamera3D::_bind_methods() {
	ClassDB::bind_method(D_METHOD("set_target_path", "target_path"), &InterpolatedCamera3D::set_target_path);
	ClassDB::bind_method(D_METHOD("get_target_path"), &InterpolatedCamera3D::get_target_path);
	ClassDB::bind_method(D_METHOD("set_target", "target"), &InterpolatedCamera3D::set_target);

	ClassDB::bind_method(D_METHOD("set_speed", "speed"), &InterpolatedCamera3D::set_speed);
	ClassDB::bind_method(D_METHOD("get_speed"), &InterpolatedCamera3D::get_speed);

	ClassDB::bind_method(D_METHOD("set_interpolation_enabled", "enabled"), &InterpolatedCamera3D::set_interpolation_enabled);
	ClassDB::bind_method(D_METHOD("is_interpolation_enabled"), &InterpolatedCamera3D::is_interpolation_enabled);

	ClassDB::bind_method(D_METHOD("set_interpolation_process_mode", "mode"), &InterpolatedCamera3D::set_interpolation_process_mode);
	ClassDB::bind_method(D_METHOD("get_interpolation_process_mode"), &InterpolatedCamera3D::get_interpolation_process_mode);

	ADD_PROPERTY(PropertyInfo(Variant::NODE_PATH, "target"), "set_target_path", "get_target_path");
	ADD_PROPERTY(PropertyInfo(Variant::FLOAT, "speed"), "set_speed", "get_speed");
	ADD_PROPERTY(PropertyInfo(Variant::BOOL, "enabled"), "set_interpolation_enabled", "is_interpolation_enabled");
	ADD_PROPERTY(PropertyInfo(Variant::INT, "interpolation_process_mode", PROPERTY_HINT_ENUM, "Physics,Idle"), "set_interpolation_process_mode", "get_interpolation_process_mode");

	BIND_ENUM_CONSTANT(PROCESS_PHYSICS);
	BIND_ENUM_CONSTANT(PROCESS_IDLE);
	BIND_ENUM_CONSTANT(INTERPOLATED_CAMERA_PROCESS_PHYSICS);
	BIND_ENUM_CONSTANT(INTERPOLATED_CAMERA_PROCESS_IDLE);
}
