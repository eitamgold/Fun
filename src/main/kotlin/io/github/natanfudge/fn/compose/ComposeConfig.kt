@file:OptIn(InternalComposeUiApi::class)

package io.github.natanfudge.fn.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.scene.PlatformLayersComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import io.github.natanfudge.fn.core.InputEvent
import io.github.natanfudge.fn.util.FunLogLevel
import io.github.natanfudge.fn.util.Lifecycle
import io.github.natanfudge.fn.util.MutEventStream
import io.github.natanfudge.fn.window.*
import kotlinx.coroutines.CoroutineExceptionHandler
import org.jetbrains.skia.*
import org.jetbrains.skia.FramebufferFormat.Companion.GR_GL_RGBA8
import org.jetbrains.skiko.FrameDispatcher
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL11.*
import org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_BINDING
import org.lwjgl.system.MemoryUtil
import java.nio.ByteBuffer


@OptIn(InternalComposeUiApi::class)
class FixedSizeComposeWindow(
    val width: Int,
    val height: Int,
    val window: ComposeGlfwWindow,
//    context: DirectContext,
) : AutoCloseable {
    // Skia Surface, bound to the OpenGL context
    val surface = glDebugGroup(0, groupName = { "Compose Surface Init" }) {
        createSurface(width, height, window.context)
    }
    val canvas = surface.canvas.asComposeCanvas()

    val frame = ByteArray(width * height * 4)
    val frameByteBuffer = MemoryUtil.memAlloc(width * height * 4)

//    init {
//        window.invalid = true
//    }

    override fun toString(): String {
        return "Compose Window w=$width, h=$height"
    }

    override fun close() {
        surface.close()
        MemoryUtil.memFree(frameByteBuffer)
    }
}

class GlfwComposeSceneContext(handle: () -> WindowHandle) : ComposeSceneContext, AutoCloseable {
    override val platformContext = GlfwComposePlatformContext(handle)

    override fun close() {
        platformContext.close()
    }
}

class GlfwComposePlatformContext(
    private val handle: () -> WindowHandle,
) : PlatformContext by PlatformContext.Empty, AutoCloseable {

    /** Cache of `glfwCreateStandardCursor` results so we don’t create the same cursor twice. */
    private val stdCursorCache = mutableMapOf<Int, Long>()

    override fun setPointerIcon(pointerIcon: PointerIcon) {
        // Pick (or build) the native cursor to install
        val cursor: Long = when (pointerIcon) {
            PointerIcon.Default -> 0L                             // Arrow
            PointerIcon.Text -> std(GLFW_IBEAM_CURSOR)           // I-beam
            PointerIcon.Crosshair -> std(GLFW_CROSSHAIR_CURSOR)     // Crosshair
            PointerIcon.Hand -> std(GLFW_HAND_CURSOR)            // Hand
            /* ---------- Anything else: just arrow ---------- */
            else -> 0L
        }


        GLFW.glfwSetCursor(handle(), cursor)
    }

    /* -------------------------------------------------- */
    /* Helper utilities                                   */
    /* -------------------------------------------------- */

    /** Lazily create -– and cache –- one of GLFW’s built-in cursors. */
    private fun std(shape: Int): Long = stdCursorCache.getOrPut(shape) { glfwCreateStandardCursor(shape) }


    /** Must be called once when you close the window. */
    override fun close() {
        stdCursorCache.values.forEach(::glfwDestroyCursor)
        stdCursorCache.clear()
    }
}

@OptIn(InternalComposeUiApi::class)
class ComposeGlfwWindow(
    initialWidth: Int,
    initialHeight: Int,
    // Background window used for OpenGL rendering
    val handle: WindowHandle,
    // Main window that displays WebGPU output
    val hostHandle: () -> WindowHandle,
    private val density: Density,
//    private val composeContent: @Composable () -> Unit,
    val config: ComposeConfig,
    private val onError: (Throwable) -> Unit,
    private val onInvalidate: () -> Unit,
) : AutoCloseable {

    init {
        GLFW.glfwMakeContextCurrent(handle)
    }


    val frameStream = MutEventStream<ComposeFrameEvent>()
    val context = DirectContext.makeGL()
    val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        System.err.println("An error occurred inside an asynchronous Compose callback. The GUI will restart itself to recover.")
        throwable.printStackTrace()
        config.windowLifecycle.restart()
        onError(throwable)
    }
    val dispatcher = GlfwCoroutineDispatcher()
    val frameDispatcher = FrameDispatcher(dispatcher) {
        // Draw new skia content
        onInvalidate()
    }
    var invalid = true

    var focused = true

    val glfwContext = GlfwComposeSceneContext(hostHandle)

    val scene = PlatformLayersComposeScene(
        coroutineContext = dispatcher + exceptionHandler,
        density = density,
        invalidate = frameDispatcher::scheduleFrame,
        size = IntSize(initialWidth, initialHeight),
        composeSceneContext = glfwContext
    )

    fun setContent(content: @Composable () -> Unit) {
        scene.setContent(content)
    }

    override fun close() {
        glfwContext.close()
        scene.close()
    }
}

private fun createSurface(width: Int, height: Int, context: DirectContext): Surface {
    val fbId = glGetInteger(GL_FRAMEBUFFER_BINDING)
    val renderTarget = BackendRenderTarget.makeGL(width, height, 0, 8, fbId, GR_GL_RGBA8)

    return Surface.makeFromBackendRenderTarget(
        context, renderTarget, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB
    )!!
}

data class ComposeFrameEvent(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

class ComposeConfig(
    host: GlfwWindowConfig,
//    val content: @Composable () -> Unit = { Text("Hello!") },
    show: Boolean = false,
    onError: (Throwable) -> Unit
) {
    companion object {
        const val LifecycleLabel = "Compose Window"
    }

    private val glfw = GlfwWindowConfig(GlfwConfig(disableApi = false, showWindow = show), name = "Compose", host.config)

    // We want this one to start early so we can update its size with the dimensions lifecycle afterwards
    val windowLifecycle: Lifecycle<GlfwWindow, ComposeGlfwWindow> = glfw.windowLifecycle.bind(LifecycleLabel, early = true) {
        glDebugGroup(1, groupName = { "Compose Init" }) {
            var window: ComposeGlfwWindow? = null
            window = ComposeGlfwWindow(
                it.init.initialWindowWidth, it.init.initialWindowHeight, it.handle,
                density = Density(glfwGetWindowContentScale(it.handle)), hostHandle = { host.windowLifecycle.assertValue.handle },
                config = this@ComposeConfig, onError = onError
            ) {
                // Invalidate Compose frame on change
                window!!.invalid = true
            }
            window
        }
    }

//    fun ComposeGlfwWindow.setContent(content: @Composable () -> Unit = { Text("Hello!") }) {
//
//    }

    val dimensionsLifecycle: Lifecycle<WindowDimensions, FixedSizeComposeWindow> =
        host.dimensionsLifecycle.bind(windowLifecycle, "Compose Fixed Size Window") { dim, window ->
            GLFW.glfwSetWindowSize(window.handle, dim.width, dim.height)
            window.scene.size = IntSize(dim.width, dim.height)

            FixedSizeComposeWindow(dim.width, dim.height, window)
        }

    init {
        // Make sure we get the frame early so we can draw it in the webgpu pass of the current frame
        // Also we need
        host.frameLifecycle.bind(dimensionsLifecycle, "Compose Frame Store", FunLogLevel.Verbose, early1 = true) { delta, dim ->
            val window = dim.window
            window.dispatcher.poll()
            if (window.invalid) {
                GLFW.glfwMakeContextCurrent(window.handle)
                dim.draw()
                glfwSwapBuffers(window.handle)
                window.invalid = false
            }
        }

    }


    private fun FixedSizeComposeWindow.draw() {
        glDebugGroup(5, groupName = { "Compose Render" }) {
            try {
                // When updates are needed - render new content
                renderSkia()
            } catch (e: Throwable) {
                System.err.println("Error during Skia rendering! This is usually a Compose user error.")
                e.printStackTrace()
            }

            // SLOW: This method of copying the frame into ByteArrays and then drawing them as a texture is extremely slow, but there
            // is probably no better alternative at the moment. We need some way to draw skia into a WebGPU context.
            // For that we need:
            // A. Skiko vulkan support
            // B. Skiko graphite support for WebGPU support
            // C. Compose skiko vulkan / webgpu / metal support
            // D. Integrate the rendering with each rendering api separately - we need to 'fetch' the vulkan context in our main webgpu app, and draw compose on top of it, and same for the other APIs.

            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, frameByteBuffer)
            val buffer = getFrameBytes()
            buffer.get(frame)

            window.frameStream.emit(ComposeFrameEvent(frame, width, height))
        }
    }


    private fun FixedSizeComposeWindow.renderSkia() {
        // Set color explicitly because skia won't reapply it every time
        glClearColor(0f, 0f, 0f, 0f)
        glDebugGroup(2, groupName = { "Compose Canvas Clear" }) {
            surface.canvas.clear(Color.TRANSPARENT)
        }

        // Render to the framebuffer
        glDebugGroup(3, groupName = { "Compose Render Content" }) {
            window.scene.render(canvas, System.nanoTime())
        }
        glDebugGroup(4, groupName = { "Compose Flush" }) {
            window.context.flush()
        }
    }

    private fun FixedSizeComposeWindow.getFrameBytes(): ByteBuffer {
        val buffer = MemoryUtil.memAlloc(width * height * 4)
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, buffer)
        return buffer
    }


    @OptIn(InternalComposeUiApi::class)
    val callbacks = object : WindowCallbacks {
        override fun onInput(input: InputEvent) {
            val window = windowLifecycle.value ?: return
            if (window.focused) {
                when (input) {
                    is InputEvent.KeyEvent -> window.scene.sendKeyEvent(input.event)
                    is InputEvent.PointerEvent -> {
                        with(input) {
                            window.scene.sendPointerEvent(
                                eventType,
                                position,
                                scrollDelta,
                                timeMillis,
                                type,
                                buttons,
                                keyboardModifiers,
                                nativeEvent,
                                button
                            )
                        }
                    }

                    else -> {}
                }
            }
        }
//        override fun pointerEvent(
//            eventType: PointerEventType,
//            position: Offset,
//            scrollDelta: Offset,
//            timeMillis: Long,
//            type: PointerType,
//            buttons: PointerButtons?,
//            keyboardModifiers: PointerKeyboardModifiers?,
//            nativeEvent: Any?,
//            button: PointerButton?,
//        ) {
//            val window = windowLifecycle.value ?: return
//            if (window.focused) {
//                window.scene.sendPointerEvent(eventType, position, scrollDelta, timeMillis, type, buttons, keyboardModifiers, nativeEvent, button)
//            }
//        }
//
//        override fun keyEvent(event: KeyEvent) {
//            val window = windowLifecycle.value ?: return
//            if (window.focused) {
//                window.scene.sendKeyEvent(event)
//            }
//        }

        override fun densityChange(newDensity: Density) {
            val window = windowLifecycle.value ?: return
            if (window.focused) {
                window.scene.density = newDensity
            }
        }
    }
}


private fun glfwGetWindowContentScale(window: Long): Float {
    val array = FloatArray(1)
    glfwGetWindowContentScale(window, array, FloatArray(1))
    return array[0]
}