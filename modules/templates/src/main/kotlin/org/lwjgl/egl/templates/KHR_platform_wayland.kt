/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.egl.templates

import org.lwjgl.generator.*
import org.lwjgl.egl.*

val KHR_platform_wayland = "KHRPlatformWayland".nativeClassEGL("KHR_platform_wayland", postfix = KHR) {
	documentation =
		"""
		Native bindings to the $registryLink extension.

		This extension defines how to create EGL resources from native Wayland resources using the EGL 1.5 platform functionality.

		Requires ${EGL15.core}.
		"""

	IntConstant(
		"",

		"PLATFORM_WAYLAND_KHR"..0x31D8
	)
}