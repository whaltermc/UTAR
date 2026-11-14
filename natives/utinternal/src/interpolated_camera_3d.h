#ifndef INTERPOLATED_CAMERA_3D_H
#define INTERPOLATED_CAMERA_3D_H

#include <godot_cpp/classes/camera3d.hpp>
#include <godot_cpp/classes/node3d.hpp>

namespace godot {

// Replacement for the game's proprietary "internal" GDExtension class.
// Behavior is a direct port of Godot 3.5's InterpolatedCamera (MIT),
// which is the class the game's internal/interpolated_camera_3d.cpp derives from.
class InterpolatedCamera3D : public Camera3D {
	GDCLASS(InterpolatedCamera3D, Camera3D)

public:
	// Godot 3.5 InterpolatedCamera::InterpolatedCameraProcessMode.
	// Both spellings are bound so scripts written against the engine fork
	// (short form) and against stock 3.5 (long form) resolve.
	//
	// NOTE: this must NOT be called ProcessMode / bound as "process_mode":
	// Node already owns a `process_mode` property (Inherit/Pausable/Always/...),
	// and shadowing it makes scene files that set Node.process_mode write into
	// the interpolation mode instead, leaving the real node mode at Inherit.
	enum InterpolationProcessMode {
		PROCESS_PHYSICS = 0,
		PROCESS_IDLE = 1,
		INTERPOLATED_CAMERA_PROCESS_PHYSICS = 0,
		INTERPOLATED_CAMERA_PROCESS_IDLE = 1,
	};

private:
	NodePath target;
	real_t speed = 1.0;
	bool enabled = false;
	bool warned_missing_target = false;
	InterpolationProcessMode interpolation_mode = PROCESS_IDLE;

protected:
	static void _bind_methods();
	void _notification(int p_what);

public:
	InterpolatedCamera3D();
	~InterpolatedCamera3D();

	void set_target_path(const NodePath &p_path);
	NodePath get_target_path() const;
	void set_target(Node3D *p_target);

	void set_speed(real_t p_speed);
	real_t get_speed() const;

	void set_interpolation_enabled(bool p_enable);
	bool is_interpolation_enabled() const;

	void set_interpolation_process_mode(InterpolationProcessMode p_mode);
	InterpolationProcessMode get_interpolation_process_mode() const;

	void _update_process_mode();

	void _process_interpolation(real_t p_delta);
};

} // namespace godot

VARIANT_ENUM_CAST(godot::InterpolatedCamera3D::InterpolationProcessMode);

#endif // INTERPOLATED_CAMERA_3D_H
