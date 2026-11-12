#include <godot_cpp/core/class_db.hpp>
#include <godot_cpp/core/defs.hpp>
#include <godot_cpp/godot.hpp>
#include <godot_cpp/classes/engine.hpp>
#include <godot_cpp/classes/resource.hpp>
#include <godot_cpp/classes/audio_stream.hpp>
#include <godot_cpp/classes/audio_stream_playback.hpp>
#include <godot_cpp/variant/utility_functions.hpp>

// FMOD Studio API 2.02.14 for Android, from the `core/inc` and `studio/inc`
// include directories (see SConstruct).
#include <fmod.hpp>
#include <fmod_studio.hpp>
#include <fmod_errors.h>

using namespace godot;

class FmodEventInstance : public Resource {
    GDCLASS(FmodEventInstance, Resource)

    FMOD::Studio::EventInstance* instance = nullptr;
    FMOD::Studio::EventDescription* description = nullptr;

protected:
    static void _bind_methods() {
        ClassDB::bind_method(D_METHOD("start"), &FmodEventInstance::start);
        ClassDB::bind_method(D_METHOD("stop", "allow_fadeout"), &FmodEventInstance::stop, DEFVAL(true));
        ClassDB::bind_method(D_METHOD("release"), &FmodEventInstance::release);
        ClassDB::bind_method(D_METHOD("set_parameter", "name", "value"), &FmodEventInstance::set_parameter);
        ClassDB::bind_method(D_METHOD("get_parameter", "name"), &FmodEventInstance::get_parameter);
        ClassDB::bind_method(D_METHOD("set_paused", "paused"), &FmodEventInstance::set_paused);
        ClassDB::bind_method(D_METHOD("is_paused"), &FmodEventInstance::is_paused);
        ClassDB::bind_method(D_METHOD("get_playback_state"), &FmodEventInstance::get_playback_state);
        ClassDB::bind_method(D_METHOD("set_volume", "volume"), &FmodEventInstance::set_volume);
        ClassDB::bind_method(D_METHOD("get_volume"), &FmodEventInstance::get_volume);
        ClassDB::bind_method(D_METHOD("set_pitch", "pitch"), &FmodEventInstance::set_pitch);
        ClassDB::bind_method(D_METHOD("get_pitch"), &FmodEventInstance::get_pitch);
        ClassDB::bind_method(D_METHOD("set_3d_attributes", "position", "velocity", "forward", "up"), &FmodEventInstance::set_3d_attributes);
    }

    ~FmodEventInstance() {
        if (instance) {
            instance->release();
            instance = nullptr;
        }
    }

public:
    void set_instance(FMOD::Studio::EventInstance* p_instance) {
        instance = p_instance;
    }

    void set_description(FMOD::Studio::EventDescription* p_description) {
        description = p_description;
    }

    void start() {
        if (instance) {
            FMOD_RESULT result = instance->start();
            if (result != FMOD_OK) {
                UtilityFunctions::push_error(vformat("FMOD: Failed to start event instance: %s", FMOD_ErrorString(result)));
            }
        }
    }

    void stop(bool p_allow_fadeout = true) {
        if (instance) {
            FMOD_STUDIO_STOP_MODE mode = p_allow_fadeout ? FMOD_STUDIO_STOP_ALLOWFADEOUT : FMOD_STUDIO_STOP_IMMEDIATE;
            FMOD_RESULT result = instance->stop(mode);
            if (result != FMOD_OK) {
                UtilityFunctions::push_error(vformat("FMOD: Failed to stop event instance: %s", FMOD_ErrorString(result)));
            }
        }
    }

    void release() {
        if (instance) {
            FMOD_RESULT result = instance->release();
            if (result != FMOD_OK) {
                UtilityFunctions::push_error(vformat("FMOD: Failed to release event instance: %s", FMOD_ErrorString(result)));
            }
            instance = nullptr;
        }
    }

    bool set_parameter(const String &p_name, float p_value) {
        if (!instance) return false;
        FMOD_RESULT result = instance->setParameterByName(p_name.utf8().get_data(), p_value);
        if (result != FMOD_OK) {
            UtilityFunctions::push_error(vformat("FMOD: Failed to set parameter '%s': %s", p_name, FMOD_ErrorString(result)));
            return false;
        }
        return true;
    }

    float get_parameter(const String &p_name) {
        if (!instance) return 0.0f;
        float value = 0.0f;
        FMOD_RESULT result = instance->getParameterByName(p_name.utf8().get_data(), &value, nullptr);
        if (result != FMOD_OK) {
            UtilityFunctions::push_error(vformat("FMOD: Failed to get parameter '%s': %s", p_name, FMOD_ErrorString(result)));
            return 0.0f;
        }
        return value;
    }

    void set_paused(bool p_paused) {
        if (instance) {
            instance->setPaused(p_paused);
        }
    }

    bool is_paused() {
        if (!instance) return true;
        bool paused = false;
        instance->getPaused(&paused);
        return paused;
    }

    int get_playback_state() {
        if (!instance) return 0;
        FMOD_STUDIO_PLAYBACK_STATE state;
        instance->getPlaybackState(&state);
        return (int)state;
    }

    void set_volume(float p_volume) {
        if (instance) {
            instance->setVolume(p_volume);
        }
    }

    float get_volume() {
        if (!instance) return 0.0f;
        float volume = 0.0f;
        instance->getVolume(&volume);
        return volume;
    }

    void set_pitch(float p_pitch) {
        if (instance) {
            instance->setPitch(p_pitch);
        }
    }

    float get_pitch() {
        if (!instance) return 1.0f;
        float pitch = 1.0f;
        instance->getPitch(&pitch);
        return pitch;
    }

    void set_3d_attributes(const Vector3 &p_position, const Vector3 &p_velocity, const Vector3 &p_forward, const Vector3 &p_up) {
        if (!instance) return;
        
        FMOD_3D_ATTRIBUTES attrs = {};
        attrs.position = {p_position.x, p_position.y, p_position.z};
        attrs.velocity = {p_velocity.x, p_velocity.y, p_velocity.z};
        attrs.forward = {p_forward.x, p_forward.y, p_forward.z};
        attrs.up = {p_up.x, p_up.y, p_up.z};
        
        instance->set3DAttributes(&attrs);
    }
};

class FmodBank : public Resource {
    GDCLASS(FmodBank, Resource)

    FMOD::Studio::Bank* bank = nullptr;
    String path;

protected:
    static void _bind_methods() {
        ClassDB::bind_method(D_METHOD("get_path"), &FmodBank::get_path);
        ClassDB::bind_method(D_METHOD("load_sample_data"), &FmodBank::load_sample_data);
        ClassDB::bind_method(D_METHOD("unload_sample_data"), &FmodBank::unload_sample_data);
    }

    ~FmodBank() {
        if (bank) {
            bank->unload();
            bank = nullptr;
        }
    }

public:
    void set_bank(FMOD::Studio::Bank* p_bank) {
        bank = p_bank;
    }

    void set_path(const String &p_path) {
        path = p_path;
    }

    String get_path() const {
        return path;
    }

    void load_sample_data() {
        if (bank) {
            FMOD_RESULT result = bank->loadSampleData();
            if (result != FMOD_OK) {
                UtilityFunctions::push_error(vformat("FMOD: Failed to load sample data for bank '%s': %s", path, FMOD_ErrorString(result)));
            }
        }
    }

    void unload_sample_data() {
        if (bank) {
            bank->unloadSampleData();
        }
    }

    FMOD::Studio::Bank* get_bank() const {
        return bank;
    }
};

class FmodBus : public Resource {
    GDCLASS(FmodBus, Resource)

    FMOD::Studio::Bus* bus = nullptr;
    String path;

protected:
    static void _bind_methods() {
        ClassDB::bind_method(D_METHOD("get_path"), &FmodBus::get_path);
        ClassDB::bind_method(D_METHOD("set_volume", "volume"), &FmodBus::set_volume);
        ClassDB::bind_method(D_METHOD("get_volume"), &FmodBus::get_volume);
        ClassDB::bind_method(D_METHOD("set_paused", "paused"), &FmodBus::set_paused);
        ClassDB::bind_method(D_METHOD("is_paused"), &FmodBus::is_paused);
        ClassDB::bind_method(D_METHOD("stop_all_events", "allow_fadeout"), &FmodBus::stop_all_events, DEFVAL(true));
    }

public:
    void set_bus(FMOD::Studio::Bus* p_bus) {
        bus = p_bus;
    }

    void set_path(const String &p_path) {
        path = p_path;
    }

    String get_path() const {
        return path;
    }

    void set_volume(float p_volume) {
        if (bus) {
            bus->setVolume(p_volume);
        }
    }

    float get_volume() {
        if (!bus) return 0.0f;
        float volume = 0.0f;
        bus->getVolume(&volume);
        return volume;
    }

    void set_paused(bool p_paused) {
        if (bus) {
            bus->setPaused(p_paused);
        }
    }

    bool is_paused() {
        if (!bus) return true;
        bool paused = false;
        bus->getPaused(&paused);
        return paused;
    }

    void stop_all_events(bool p_allow_fadeout = true) {
        if (bus) {
            FMOD_STUDIO_STOP_MODE mode = p_allow_fadeout ? FMOD_STUDIO_STOP_ALLOWFADEOUT : FMOD_STUDIO_STOP_IMMEDIATE;
            bus->stopAllEvents(mode);
        }
    }
};

class FmodVCA : public Resource {
    GDCLASS(FmodVCA, Resource)

    FMOD::Studio::VCA* vca = nullptr;
    String path;

protected:
    static void _bind_methods() {
        ClassDB::bind_method(D_METHOD("get_path"), &FmodVCA::get_path);
        ClassDB::bind_method(D_METHOD("set_volume", "volume"), &FmodVCA::set_volume);
        ClassDB::bind_method(D_METHOD("get_volume"), &FmodVCA::get_volume);
    }

public:
    void set_vca(FMOD::Studio::VCA* p_vca) {
        vca = p_vca;
    }

    void set_path(const String &p_path) {
        path = p_path;
    }

    String get_path() const {
        return path;
    }

    void set_volume(float p_volume) {
        if (vca) {
            vca->setVolume(p_volume);
        }
    }

    float get_volume() {
        if (!vca) return 0.0f;
        float volume = 0.0f;
        vca->getVolume(&volume);
        return volume;
    }
};

class Fmod : public Object {
    GDCLASS(Fmod, Object)

    FMOD::Studio::System* studio_system = nullptr;
    FMOD::System* core_system = nullptr;
    bool initialized = false;

protected:
    static void _bind_methods() {
        ClassDB::bind_method(D_METHOD("initialize", "max_channels", "studio_flags", "core_flags"), &Fmod::initialize, DEFVAL(512), DEFVAL(0), DEFVAL(0));
        ClassDB::bind_method(D_METHOD("shutdown"), &Fmod::shutdown);
        ClassDB::bind_method(D_METHOD("is_initialized"), &Fmod::is_initialized);
        ClassDB::bind_method(D_METHOD("update"), &Fmod::update);
        
        ClassDB::bind_method(D_METHOD("load_bank", "path", "flags"), &Fmod::load_bank, DEFVAL(0));
        ClassDB::bind_method(D_METHOD("unload_bank", "bank"), &Fmod::unload_bank);
        ClassDB::bind_method(D_METHOD("load_bank_file", "path", "flags"), &Fmod::load_bank_file, DEFVAL(0));
        
        ClassDB::bind_method(D_METHOD("create_event_instance", "path"), &Fmod::create_event_instance);
        ClassDB::bind_method(D_METHOD("get_event_description", "path"), &Fmod::get_event_description);
        
        ClassDB::bind_method(D_METHOD("get_bus", "path"), &Fmod::get_bus);
        ClassDB::bind_method(D_METHOD("get_vca", "path"), &Fmod::get_vca);
        
        ClassDB::bind_method(D_METHOD("set_listener_attributes", "listener_index", "position", "velocity", "forward", "up"), &Fmod::set_listener_attributes);
        ClassDB::bind_method(D_METHOD("get_listener_attributes", "listener_index"), &Fmod::get_listener_attributes);
        
        ClassDB::bind_method(D_METHOD("set_global_parameter", "name", "value"), &Fmod::set_global_parameter);
        ClassDB::bind_method(D_METHOD("get_global_parameter", "name"), &Fmod::get_global_parameter);
        
        ClassDB::bind_method(D_METHOD("get_core_system"), &Fmod::get_core_system);
        
        ClassDB::bind_method(D_METHOD("get_memory_stats"), &Fmod::get_memory_stats);
        ClassDB::bind_method(D_METHOD("get_cpu_usage"), &Fmod::get_cpu_usage);
    }

    ~Fmod() {
        shutdown();
    }

public:
    bool initialize(int p_max_channels = 512, int p_studio_flags = 0, int p_core_flags = 0) {
        if (initialized) {
            UtilityFunctions::push_warning("FMOD: Already initialized");
            return true;
        }

        FMOD_RESULT result;

        result = FMOD::Studio::System::create(&studio_system);
        if (result != FMOD_OK) {
            UtilityFunctions::push_error(vformat("FMOD: Failed to create Studio system: %s", FMOD_ErrorString(result)));
            return false;
        }

        result = studio_system->getCoreSystem(&core_system);
        if (result != FMOD_OK) {
            UtilityFunctions::push_error(vformat("FMOD: Failed to get Core system: %s", FMOD_ErrorString(result)));
            return false;
        }

        result = core_system->setSoftwareFormat(0, FMOD_SPEAKERMODE_DEFAULT, 0);
        if (result != FMOD_OK) {
            UtilityFunctions::push_warning(vformat("FMOD: Failed to set software format: %s", FMOD_ErrorString(result)));
        }

        result = studio_system->initialize(p_max_channels, p_studio_flags, p_core_flags, nullptr);
        if (result != FMOD_OK) {
            UtilityFunctions::push_error(vformat("FMOD: Failed to initialize Studio system: %s", FMOD_ErrorString(result)));
            studio_system->release();
            studio_system = nullptr;
            core_system = nullptr;
            return false;
        }

        initialized = true;
        UtilityFunctions::print("FMOD: Initialized successfully");
        return true;
    }

    void shutdown() {
        if (!initialized) return;

        if (studio_system) {
            studio_system->unloadAll();
            FMOD_RESULT result = studio_system->release();
            if (result != FMOD_OK) {
                UtilityFunctions::push_error(vformat("FMOD: Failed to release Studio system: %s", FMOD_ErrorString(result)));
            }
            studio_system = nullptr;
            core_system = nullptr;
        }

        initialized = false;
        UtilityFunctions::print("FMOD: Shutdown complete");
    }

    bool is_initialized() const {
        return initialized;
    }

    void update() {
        if (studio_system) {
            studio_system->update();
        }
    }

    Ref<FmodBank> load_bank(const String &p_path, int p_flags = 0) {
        if (!initialized || !studio_system) {
            UtilityFunctions::push_error("FMOD: Not initialized");
            return Ref<FmodBank>();
        }

        FMOD::Studio::Bank* bank = nullptr;
        FMOD_RESULT result = studio_system->loadBankFile(p_path.utf8().get_data(), (FMOD_STUDIO_LOAD_BANK_FLAGS)p_flags, &bank);
        
        if (result != FMOD_OK) {
            UtilityFunctions::push_error(vformat("FMOD: Failed to load bank '%s': %s", p_path, FMOD_ErrorString(result)));
            return Ref<FmodBank>();
        }

        Ref<FmodBank> bank_ref;
        bank_ref.instantiate();
        bank_ref->set_bank(bank);
        bank_ref->set_path(p_path);
        return bank_ref;
    }

    Ref<FmodBank> load_bank_file(const String &p_path, int p_flags = 0) {
        return load_bank(p_path, p_flags);
    }

    void unload_bank(const Ref<FmodBank> &p_bank) {
        if (!p_bank.is_valid() || !p_bank->get_bank()) return;
        p_bank->get_bank()->unload();
    }

    Ref<FmodEventInstance> create_event_instance(const String &p_path) {
        if (!initialized || !studio_system) {
            UtilityFunctions::push_error("FMOD: Not initialized");
            return Ref<FmodEventInstance>();
        }

        FMOD::Studio::EventDescription* description = nullptr;
        FMOD_RESULT result = studio_system->getEvent(p_path.utf8().get_data(), &description);
        
        if (result != FMOD_OK) {
            UtilityFunctions::push_error(vformat("FMOD: Failed to get event '%s': %s", p_path, FMOD_ErrorString(result)));
            return Ref<FmodEventInstance>();
        }

        FMOD::Studio::EventInstance* instance = nullptr;
        result = description->createInstance(&instance);
        
        if (result != FMOD_OK) {
            UtilityFunctions::push_error(vformat("FMOD: Failed to create instance for event '%s': %s", p_path, FMOD_ErrorString(result)));
            return Ref<FmodEventInstance>();
        }

        Ref<FmodEventInstance> instance_ref;
        instance_ref.instantiate();
        instance_ref->set_instance(instance);
        instance_ref->set_description(description);
        return instance_ref;
    }

    Ref<FmodEventInstance> get_event_description(const String &p_path) {
        return create_event_instance(p_path);
    }

    Ref<FmodBus> get_bus(const String &p_path) {
        if (!initialized || !studio_system) {
            UtilityFunctions::push_error("FMOD: Not initialized");
            return Ref<FmodBus>();
        }

        FMOD::Studio::Bus* bus = nullptr;
        FMOD_RESULT result = studio_system->getBus(p_path.utf8().get_data(), &bus);
        
        if (result != FMOD_OK) {
            UtilityFunctions::push_error(vformat("FMOD: Failed to get bus '%s': %s", p_path, FMOD_ErrorString(result)));
            return Ref<FmodBus>();
        }

        Ref<FmodBus> bus_ref;
        bus_ref.instantiate();
        bus_ref->set_bus(bus);
        bus_ref->set_path(p_path);
        return bus_ref;
    }

    Ref<FmodVCA> get_vca(const String &p_path) {
        if (!initialized || !studio_system) {
            UtilityFunctions::push_error("FMOD: Not initialized");
            return Ref<FmodVCA>();
        }

        FMOD::Studio::VCA* vca = nullptr;
        FMOD_RESULT result = studio_system->getVCA(p_path.utf8().get_data(), &vca);
        
        if (result != FMOD_OK) {
            UtilityFunctions::push_error(vformat("FMOD: Failed to get VCA '%s': %s", p_path, FMOD_ErrorString(result)));
            return Ref<FmodVCA>();
        }

        Ref<FmodVCA> vca_ref;
        vca_ref.instantiate();
        vca_ref->set_vca(vca);
        vca_ref->set_path(p_path);
        return vca_ref;
    }

    void set_listener_attributes(int p_listener_index, const Vector3 &p_position, const Vector3 &p_velocity, const Vector3 &p_forward, const Vector3 &p_up) {
        if (!core_system) return;
        
        FMOD_VECTOR pos = {p_position.x, p_position.y, p_position.z};
        FMOD_VECTOR vel = {p_velocity.x, p_velocity.y, p_velocity.z};
        FMOD_VECTOR fwd = {p_forward.x, p_forward.y, p_forward.z};
        FMOD_VECTOR up = {p_up.x, p_up.y, p_up.z};
        
        core_system->set3DListenerAttributes(p_listener_index, &pos, &vel, &fwd, &up);
    }

    Dictionary get_listener_attributes(int p_listener_index) {
        Dictionary attrs;
        if (!core_system) return attrs;
        
        FMOD_VECTOR pos, vel, fwd, up;
        FMOD_RESULT result = core_system->get3DListenerAttributes(p_listener_index, &pos, &vel, &fwd, &up);
        if (result != FMOD_OK) {
            return attrs;
        }
        
        attrs["position"] = Vector3(pos.x, pos.y, pos.z);
        attrs["velocity"] = Vector3(vel.x, vel.y, vel.z);
        attrs["forward"] = Vector3(fwd.x, fwd.y, fwd.z);
        attrs["up"] = Vector3(up.x, up.y, up.z);
        
        return attrs;
    }

    bool set_global_parameter(const String &p_name, float p_value) {
        if (!studio_system) return false;
        FMOD_RESULT result = studio_system->setParameterByName(p_name.utf8().get_data(), p_value);
        return result == FMOD_OK;
    }

    float get_global_parameter(const String &p_name) {
        if (!studio_system) return 0.0f;
        float value = 0.0f;
        FMOD_RESULT result = studio_system->getParameterByName(p_name.utf8().get_data(), &value, nullptr);
        return result == FMOD_OK ? value : 0.0f;
    }

    Fmod *get_core_system() {
        return this;
    }

    Dictionary get_memory_stats() {
        Dictionary stats;
        int current = 0, max = 0;
        FMOD_RESULT result = FMOD_Memory_GetStats(&current, &max, false);
        if (result == FMOD_OK) {
            stats["current"] = current;
            stats["max"] = max;
        }
        return stats;
    }

    Dictionary get_cpu_usage() {
        Dictionary usage;
        if (!core_system) return usage;
        
        FMOD_CPU_USAGE cpu_usage;
        FMOD_RESULT result = core_system->getCPUUsage(&cpu_usage);
        if (result != FMOD_OK) {
            return usage;
        }
        
        usage["dsp"] = cpu_usage.dsp * 100.0;
        usage["stream"] = cpu_usage.stream * 100.0;
        usage["geometry"] = cpu_usage.geometry * 100.0;
        usage["update"] = cpu_usage.update * 100.0;
        usage["convolution1"] = cpu_usage.convolution1 * 100.0;
        usage["convolution2"] = cpu_usage.convolution2 * 100.0;
        
        return usage;
    }
};

void initialize_fmod_module(ModuleInitializationLevel p_level) {
    if (p_level != MODULE_INITIALIZATION_LEVEL_SCENE) {
        return;
    }
    ClassDB::register_class<Fmod>();
    ClassDB::register_class<FmodBank>();
    ClassDB::register_class<FmodEventInstance>();
    ClassDB::register_class<FmodBus>();
    ClassDB::register_class<FmodVCA>();
}

void uninitialize_fmod_module(ModuleInitializationLevel p_level) {
    if (p_level != MODULE_INITIALIZATION_LEVEL_SCENE) {
        return;
    }
}

extern "C" {
    GDExtensionBool GDE_EXPORT fmod_library_init(GDExtensionInterfaceGetProcAddress p_get_proc_address, GDExtensionClassLibraryPtr p_library, GDExtensionInitialization *r_initialization) {
        godot::GDExtensionBinding::InitObject init_obj(p_get_proc_address, p_library, r_initialization);
        init_obj.register_initializer(initialize_fmod_module);
        init_obj.register_terminator(uninitialize_fmod_module);
        init_obj.set_minimum_library_initialization_level(MODULE_INITIALIZATION_LEVEL_SCENE);
        return init_obj.init();
    }
}