#include "colorizer.h"

#include <godot_cpp/classes/random_number_generator.hpp>
#include <godot_cpp/core/class_db.hpp>

namespace godot {

namespace {

struct ColorRange {
	float s_min;
	float s_max;
	float v_min;
	float v_max;
};

ColorRange range_for_component(const String &p_component) {
	if (p_component == "hair") {
		return { 0.15f, 0.60f, 0.15f, 0.70f };
	}
	if (p_component == "bottom") {
		return { 0.05f, 0.45f, 0.20f, 0.65f };
	}
	if (p_component == "top") {
		return { 0.05f, 0.50f, 0.40f, 0.90f };
	}
	if (p_component == "facemask") {
		return { 0.00f, 0.25f, 0.60f, 0.95f };
	}
	// skin is shipped pre-tinted; behave as a no-op if it ever reaches us.
	return { 0.0f, 0.0f, 1.0f, 1.0f };
}

} // namespace

Colorizer::Colorizer() {}
Colorizer::~Colorizer() {}

void Colorizer::_bind_methods() {
	ClassDB::bind_method(D_METHOD("set_gen_seed", "seed"), &Colorizer::set_gen_seed);
	ClassDB::bind_method(D_METHOD("get_gen_seed"), &Colorizer::get_gen_seed);
	ClassDB::bind_method(D_METHOD("manipulate", "image", "component", "gender"), &Colorizer::manipulate);

	ADD_PROPERTY(PropertyInfo(Variant::INT, "gen_seed"), "set_gen_seed", "get_gen_seed");
}

void Colorizer::set_gen_seed(int p_seed) {
	gen_seed = p_seed;
}

int Colorizer::get_gen_seed() const {
	return gen_seed;
}

void Colorizer::manipulate(const Ref<Image> &p_image, const String &p_component, int p_gender) {
	if (p_image.is_null() || p_image->is_empty()) {
		return;
	}
	if (p_component == "skin") {
		return;
	}

	ColorRange range = range_for_component(p_component);
	if (range.s_max <= 0.0f && range.v_min >= 1.0f) {
		return;
	}

	if (p_image->get_format() != Image::FORMAT_RGBA8) {
		p_image->convert(Image::FORMAT_RGBA8);
	}

	// Same seed recipe the game's own helper uses:
	//   gen_seed + component.hash() + i * 23
	// (i is 0 here, gender folds in so male/female variants differ).
	RandomNumberGenerator rng;
	uint64_t seed = static_cast<uint64_t>(
			static_cast<int64_t>(gen_seed) + p_component.hash() + static_cast<int64_t>(p_gender) * 7919);
	rng.set_seed(seed);

	Color tint;
	tint.set_hsv(rng.randf_range(0.0, 1.0),
			rng.randf_range(range.s_min, range.s_max),
			rng.randf_range(range.v_min, range.v_max));

	float tint_value = tint.r;
	if (tint.g > tint_value) {
		tint_value = tint.g;
	}
	if (tint.b > tint_value) {
		tint_value = tint.b;
	}
	if (tint_value <= 0.0001f) {
		return;
	}

	const int width = p_image->get_width();
	const int height = p_image->get_height();

	for (int y = 0; y < height; y++) {
		for (int x = 0; x < width; x++) {
			const Color src = p_image->get_pixel(x, y);
			if (src.a <= 0.003f) {
				continue;
			}
			// Only recolor near-greyscale pixels so coloured skin presets,
			// eyes and other hand-tinted details stay untouched.
			if (src.get_s() > 0.15f) {
				continue;
			}
			const float scale = src.get_luminance() / tint_value;
			Color out(tint.r * scale, tint.g * scale, tint.b * scale, src.a);
			p_image->set_pixel(x, y, out);
		}
	}
}

} // namespace godot
