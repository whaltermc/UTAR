// Runtime-only registration for the fmod-for-godot addon (MIT), following UTAP's
// fmod_scene_register.cpp: the addon's editor classes are omitted because a release
// game never initializes MODULE_INITIALIZATION_LEVEL_EDITOR, while the editor module
// source is still compiled so FMODSettings keeps its real implementation.
//
// The entry symbol must stay fmod_library_init: the pack's own
// res://addons/fmod/fmod.gdextension (entry_symbol = "fmod_library_init") is what
// GdextensionRewriter maps onto libfmod_extension.so.

#include "api/studio_api.h"
#include "fmod_assets.h"
#include "fmod_studio_editor_module.h"
#include "fmod_studio_module.h"
#include "fmod_types.h"
#include "scene/studio_bank_loader.h"
#include "scene/studio_event_emitter.h"
#include "scene/studio_global_parameter_trigger.h"
#include "scene/studio_listener.h"
#include "scene/studio_parameter_trigger.h"
#include "utils/debug_monitors.h"
#include "utils/runtime_utils.h"

#include <gdextension_interface.h>
#include <godot_cpp/classes/engine.hpp>
#include <godot_cpp/core/class_db.hpp>
#include <godot_cpp/core/defs.hpp>
#include <godot_cpp/godot.hpp>

using namespace godot;

static FMODStudioModule *fmod_module = nullptr;
static FMODSettings *fmod_settings = nullptr;

static void register_fmod_scene_types(ModuleInitializationLevel p_level)
{
	if (p_level != MODULE_INITIALIZATION_LEVEL_SCENE)
	{
		return;
	}

	ClassDB::register_class<FMODStudioModule>();
	fmod_module = memnew(FMODStudioModule);
	Engine::get_singleton()->register_singleton("FMODStudioModule", FMODStudioModule::get_singleton());

	ClassDB::register_class<FMODSettings>();
	fmod_settings = memnew(FMODSettings);

	// Studio API
	ClassDB::register_class<StudioApi::StudioSystem>();
	ClassDB::register_class<StudioApi::EventDescription>();
	ClassDB::register_class<StudioApi::EventInstance>();
	ClassDB::register_class<StudioApi::Bus>();
	ClassDB::register_class<StudioApi::Bank>();
	ClassDB::register_class<StudioApi::VCA>();
	ClassDB::register_class<StudioApi::CommandReplay>();

	// FMOD specific types
	ClassDB::register_class<FmodTypes::FMOD_STUDIO_PARAMETER_ID>();
	ClassDB::register_class<FmodTypes::FMOD_STUDIO_PARAMETER_DESCRIPTION>();
	ClassDB::register_class<FmodTypes::FMOD_3D_ATTRIBUTES>();
	ClassDB::register_class<FmodTypes::FMOD_STUDIO_BUFFER_INFO>();
	ClassDB::register_class<FmodTypes::FMOD_STUDIO_BUFFER_USAGE>();
	ClassDB::register_class<FmodTypes::FMOD_STUDIO_CPU_USAGE>();
	ClassDB::register_class<FmodTypes::FMOD_CPU_USAGE>();
	ClassDB::register_class<FmodTypes::FMOD_STUDIO_MEMORY_USAGE>();
	ClassDB::register_class<FmodTypes::FMOD_STUDIO_USER_PROPERTY>();

	// FMOD Resources
	ClassDB::register_class<FMODAsset>();
	ClassDB::register_class<BankAsset>();
	ClassDB::register_class<EventAsset>();
	ClassDB::register_class<BusAsset>();
	ClassDB::register_class<VCAAsset>();
	ClassDB::register_class<ParameterAsset>();

	// Runtime Utils
	ClassDB::register_class<RuntimeUtils>();

	// Custom nodes
	ClassDB::register_class<StudioEventEmitter3D>();
	ClassDB::register_class<StudioEventEmitter2D>();
	ClassDB::register_class<StudioParameterTrigger>();
	ClassDB::register_class<StudioGlobalParameterTrigger>();
	ClassDB::register_class<StudioBankLoader>();
	ClassDB::register_class<StudioListener3D>();
	ClassDB::register_class<StudioListener2D>();

	// Custom performance monitors
	ClassDB::register_class<FMODDebugMonitor>();
}

static void unregister_fmod_scene_types(ModuleInitializationLevel p_level)
{
	if (p_level != MODULE_INITIALIZATION_LEVEL_SCENE)
	{
		return;
	}

	Engine::get_singleton()->unregister_singleton("FMODStudioModule");
	memdelete(fmod_module);
	fmod_module = nullptr;
}

extern "C"
{
	// Initialization.

	GDExtensionBool GDE_EXPORT fmod_library_init(GDExtensionInterfaceGetProcAddress p_get_proc_address,
			const GDExtensionClassLibraryPtr p_library,
			GDExtensionInitialization* r_initialization)
	{
		godot::GDExtensionBinding::InitObject init_obj(p_get_proc_address, p_library, r_initialization);

		init_obj.register_initializer(register_fmod_scene_types);
		init_obj.register_terminator(unregister_fmod_scene_types);
		init_obj.set_minimum_library_initialization_level(MODULE_INITIALIZATION_LEVEL_SCENE);

		return init_obj.init();
	}
}
