# projectM picks a random transition per preset change, and most of them wipe the new preset in behind a moving
# edge. Keep only its plain crossfade so presets blend smoothly.
set(source "src/libprojectM/Renderer/TransitionShaderManager.cpp")
file(READ "${source}" code)
set(transitions [=[m_transitionShaders({CompileTransitionShader(kTransitionShaderBuiltInCircleGlsl330),
                           CompileTransitionShader(kTransitionShaderBuiltInPlasmaGlsl330),
                           CompileTransitionShader(kTransitionShaderBuiltInSimpleBlendGlsl330),
                           CompileTransitionShader(kTransitionShaderBuiltInSweepGlsl330),
                           CompileTransitionShader(kTransitionShaderBuiltInWarpGlsl330),
                           CompileTransitionShader(kTransitionShaderBuiltInZoomBlurGlsl330)})]=])
set(crossfade "m_transitionShaders({CompileTransitionShader(kTransitionShaderBuiltInSimpleBlendGlsl330)})")
string(FIND "${code}" "${crossfade}" patched)
if(patched EQUAL -1)
    string(FIND "${code}" "${transitions}" found)
    if(found EQUAL -1)
        message(FATAL_ERROR "projectM's transition list changed; update native/projectm-crossfade.cmake")
    endif()
    string(REPLACE "${transitions}" "${crossfade}" code "${code}")
    file(WRITE "${source}" "${code}")
endif()
