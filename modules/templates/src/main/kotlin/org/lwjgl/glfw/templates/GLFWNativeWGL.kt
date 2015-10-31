/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.glfw.templates

import org.lwjgl.generator.*
import org.lwjgl.glfw.*
import org.lwjgl.system.windows.*

val GLFWNativeWGL = "GLFWNativeWGL".nativeClass(packageName = GLFW_PACKAGE, nativeSubPath = "windows", prefix = "GLFW", binding = GLFWBinding) {
	documentation = "Native bindings to the GLFW library's WGL native access functions."

	HGLRC(
		"GetWGLContext",
		"""
		Returns the ${code("HGLRC")} of the specified window.

		Note: This function may be called from any thread. Access is not synchronized.
		""",

		GLFWwindow.IN("window", "the GLFW window"),
		returnDoc = "The ${code("HGLRC")} of the specified window, or $NULL if an error occurred.",
		since = "GLFW 3.0"
	)
}