#include <jni.h>
#include <projectM-4/projectM.h>
#include <algorithm>
#include <array>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#ifdef __ANDROID__
#include <GLES3/gl3.h>
#else
#include <SDL.h>
#include <SDL_opengl.h>
#endif

namespace {
struct Visualizer {
    projectm_handle engine = nullptr;
    std::string error;
    int width = 0, height = 0;
#ifndef __ANDROID__
    SDL_Window* window = nullptr;
    SDL_GLContext context = nullptr;
#endif
    ~Visualizer() {
        if (engine) projectm_destroy(engine);
#ifndef __ANDROID__
        if (context) SDL_GL_DeleteContext(context);
        if (window) SDL_DestroyWindow(window);
        SDL_QuitSubSystem(SDL_INIT_VIDEO);
#endif
    }
};
Visualizer& instance(jlong handle) {
    if (!handle) throw std::runtime_error("MilkDrop is closed");
    return *reinterpret_cast<Visualizer*>(handle);
}
void fail(JNIEnv* env, const std::exception& error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
void presetFailed(const char*, const char* message, void* data) {
    static_cast<Visualizer*>(data)->error = message ? message : "Could not load this preset";
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_me_d3sox_nevumi_visualizer_ProjectMNative_create(JNIEnv* env, jobject, jint width, jint height, jboolean desktop, jstring textures) {
    try {
        auto visualizer = std::make_unique<Visualizer>();
#ifndef __ANDROID__
        if (!desktop) throw std::runtime_error("A desktop visualizer needs its own window");
        if (SDL_InitSubSystem(SDL_INIT_VIDEO) != 0) throw std::runtime_error(SDL_GetError());
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 3);
        SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_CORE);
        SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER, 1);
        visualizer->window = SDL_CreateWindow("Nevumi MilkDrop | N/P presets | F fullscreen | Esc close",
            SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED, width, height,
            SDL_WINDOW_OPENGL | SDL_WINDOW_RESIZABLE | SDL_WINDOW_ALLOW_HIGHDPI);
        if (!visualizer->window) throw std::runtime_error(SDL_GetError());
        visualizer->context = SDL_GL_CreateContext(visualizer->window);
        if (!visualizer->context) throw std::runtime_error(SDL_GetError());
        SDL_GL_SetSwapInterval(1);
#else
        if (desktop) throw std::runtime_error("Android supplies the visualizer's OpenGL context");
#endif
        visualizer->engine = projectm_create();
        if (!visualizer->engine) throw std::runtime_error("MilkDrop requires OpenGL 3.3 or OpenGL ES 3.0");
        const char* texturePath = env->GetStringUTFChars(textures, nullptr);
        if (!texturePath) return 0;
        std::string textureDirectory(texturePath);
        env->ReleaseStringUTFChars(textures, texturePath);
        const char* searchPaths[] = { textureDirectory.c_str() };
        projectm_set_texture_search_paths(visualizer->engine, searchPaths, 1);
        projectm_set_preset_switch_failed_event_callback(visualizer->engine, presetFailed, visualizer.get());
        projectm_set_mesh_size(visualizer->engine, 32, 24);
        projectm_set_fps(visualizer->engine, 30);
        projectm_set_preset_locked(visualizer->engine, true);
        projectm_set_hard_cut_enabled(visualizer->engine, false);
        projectm_set_soft_cut_duration(visualizer->engine, 2.0);
        projectm_set_window_size(visualizer->engine, width, height);
        visualizer->width = width; visualizer->height = height;
        return reinterpret_cast<jlong>(visualizer.release());
    } catch (const std::exception& error) { fail(env, error); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_me_d3sox_nevumi_visualizer_ProjectMNative_load(JNIEnv* env, jobject, jlong handle, jstring path, jboolean smooth) {
    try {
        auto& visualizer = instance(handle);
        const char* value = env->GetStringUTFChars(path, nullptr);
        if (!value) return;
        std::string filename(value);
        env->ReleaseStringUTFChars(path, value);
        visualizer.error.clear();
        projectm_load_preset_file(visualizer.engine, filename.c_str(), smooth);
        if (!visualizer.error.empty()) throw std::runtime_error(visualizer.error);
    } catch (const std::exception& error) { fail(env, error); }
}

extern "C" JNIEXPORT void JNICALL
Java_me_d3sox_nevumi_visualizer_ProjectMNative_loadData(JNIEnv* env, jobject, jlong handle, jbyteArray preset, jboolean smooth) {
    try {
        auto& visualizer = instance(handle);
        std::string data(static_cast<size_t>(env->GetArrayLength(preset)), '\0');
        env->GetByteArrayRegion(preset, 0, static_cast<jsize>(data.size()), reinterpret_cast<jbyte*>(data.data()));
        visualizer.error.clear();
        projectm_load_preset_data(visualizer.engine, data.c_str(), smooth);
        if (!visualizer.error.empty()) throw std::runtime_error(visualizer.error);
    } catch (const std::exception& error) { fail(env, error); }
}

extern "C" JNIEXPORT jint JNICALL
Java_me_d3sox_nevumi_visualizer_ProjectMNative_render(JNIEnv* env, jobject, jlong handle, jfloatArray pcm, jint width, jint height, jboolean animate) {
    try {
        auto& visualizer = instance(handle);
        int commands = 0;
#ifndef __ANDROID__
        SDL_Event event;
        while (SDL_PollEvent(&event)) {
            if (event.type == SDL_QUIT || (event.type == SDL_WINDOWEVENT && event.window.event == SDL_WINDOWEVENT_CLOSE)) commands |= 4;
            if (event.type == SDL_KEYDOWN && !event.key.repeat) {
                switch (event.key.keysym.sym) {
                    case SDLK_ESCAPE: commands |= 4; break;
                    case SDLK_n: case SDLK_RIGHT: case SDLK_SPACE: commands |= 1; break;
                    case SDLK_p: case SDLK_LEFT: commands |= 2; break;
                    case SDLK_f: case SDLK_F11:
                        SDL_SetWindowFullscreen(visualizer.window,
                            SDL_GetWindowFlags(visualizer.window) & SDL_WINDOW_FULLSCREEN_DESKTOP ? 0 : SDL_WINDOW_FULLSCREEN_DESKTOP);
                        break;
                }
            }
        }
        SDL_GL_GetDrawableSize(visualizer.window, &width, &height);
#endif
        if (width <= 0 || height <= 0 || commands & 4 || !animate) return commands;
        if (width != visualizer.width || height != visualizer.height) {
            projectm_set_window_size(visualizer.engine, width, height);
            visualizer.width = width; visualizer.height = height;
        }
        const auto count = std::min(static_cast<unsigned int>(env->GetArrayLength(pcm) / 2), projectm_pcm_get_max_samples());
        if (count) {
            auto* samples = env->GetFloatArrayElements(pcm, nullptr);
            if (!samples) return commands;
            projectm_pcm_add_float(visualizer.engine, samples, count, PROJECTM_STEREO);
            env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
        } else {
            const std::array<float, 1024> silence{};
            projectm_pcm_add_float(visualizer.engine, silence.data(), 512, PROJECTM_STEREO);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);
        projectm_opengl_render_frame(visualizer.engine);
#ifndef __ANDROID__
        SDL_GL_SwapWindow(visualizer.window);
#endif
        return commands;
    } catch (const std::exception& error) { fail(env, error); return 4; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_me_d3sox_nevumi_visualizer_ProjectMNative_pixels(JNIEnv* env, jobject, jlong handle) {
    try {
        auto& visualizer = instance(handle);
        const auto size = static_cast<size_t>(visualizer.width) * visualizer.height * 4;
        if (size > 32 * 1024 * 1024) throw std::runtime_error("Visualizer capture is too large");
        std::vector<GLubyte> pixels(size);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
#ifndef __ANDROID__
        // Back-buffer contents are undefined after swap. Draw the capture before presenting it.
        glReadBuffer(GL_BACK);
        glViewport(0, 0, visualizer.width, visualizer.height);
        projectm_opengl_render_frame(visualizer.engine);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
#endif
        glReadPixels(0, 0, visualizer.width, visualizer.height, GL_RGBA, GL_UNSIGNED_BYTE, pixels.data());
        auto result = env->NewByteArray(static_cast<jsize>(size));
        if (result) env->SetByteArrayRegion(result, 0, static_cast<jsize>(size), reinterpret_cast<jbyte*>(pixels.data()));
        return result;
    } catch (const std::exception& error) { fail(env, error); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_me_d3sox_nevumi_visualizer_ProjectMNative_destroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Visualizer*>(handle);
}
