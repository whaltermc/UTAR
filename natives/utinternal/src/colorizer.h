#ifndef COLORIZER_H
#define COLORIZER_H

#include <godot_cpp/classes/image.hpp>
#include <godot_cpp/classes/node.hpp>
#include <godot_cpp/variant/string.hpp>

namespace godot {

// Replacement for the game's proprietary "Colorizer" GDExtension class.
//
// NpcBasic/NpcMask instantiate this as a child node, call set_gen_seed() once
// and then manipulate() for every component/frame image before slicing them
// into SpriteFrames. The generated result only has to be deterministic for a
// given seed, so all randomness is derived from gen_seed + component name.
class Colorizer : public Node {
	GDCLASS(Colorizer, Node)

private:
	int gen_seed = 0;

protected:
	static void _bind_methods();

public:
	Colorizer();
	~Colorizer();

	void set_gen_seed(int p_seed);
	int get_gen_seed() const;

	// Recolors the neutral (grey) pixels of `image` in place. The shipped
	// textures already carry coloured skin tones, so only hair/clothing/mask
	// layers are tinted while their original shading is preserved.
	void manipulate(const Ref<Image> &image, const String &component, int gender);
};

} // namespace godot

#endif // COLORIZER_H
