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

	NodePath target;
	real_t speed = 1.0;
	bool enabled = false;

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

	void _update_process_mode();
};

} // namespace godot

#endif // INTERPOLATED_CAMERA_3D_H
