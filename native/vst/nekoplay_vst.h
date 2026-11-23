#pragma once

#ifdef _WIN32
#define NV_API extern "C" __declspec(dllexport)
#else
#define NV_API extern "C" __attribute__((visibility("default")))
#endif

// Lifecycle
NV_API int nv_init();
NV_API void nv_shutdown();

// Real-time PCM engine (libmpv ao=pcm -> named pipe -> VST chain -> miniaudio)
// Returns allocated UTF-8 pipe path (free with nv_free_str), or nullptr on error.
NV_API const char* nv_start_engine(int sampleRate, int channels);
NV_API void nv_stop_engine();
NV_API int nv_engine_running();

// Plugin scanning: dirsJson is a JSON array of extra directories (or nullptr).
// Returns allocated JSON array: [{"path":"...","name":"...","format":"VST3"|"VST2"}]
NV_API const char* nv_scan_dirs(const char* dirsJson);

// Plugin chain management
NV_API int nv_add_plugin(const char* path);
NV_API int nv_remove_plugin(int id);
NV_API int nv_move_plugin(int id, int newIndex);
NV_API int nv_set_bypass(int id, int bypass);
NV_API const char* nv_get_chain();

// Parameters
NV_API const char* nv_get_params(int id);
NV_API int nv_set_param(int id, int index, float value);

// Native Plugin GUI window (supports always-on-top floating mode for MiniMeters / EQ / etc.)
NV_API int nv_open_editor(int id, int alwaysOnTop);
NV_API int nv_set_editor_ontop(int id, int alwaysOnTop);
NV_API int nv_close_editor(int id);

// Utilities
NV_API const char* nv_last_error();
NV_API void nv_free_str(const char* s);
