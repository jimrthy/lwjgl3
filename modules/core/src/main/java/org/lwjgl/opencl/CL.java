/*
 * Copyright LWJGL. All rights reserved.
 * License terms: http://lwjgl.org/license.php
 */
package org.lwjgl.opencl;

import org.lwjgl.LWJGLUtil;
import org.lwjgl.LWJGLUtil.Platform;
import org.lwjgl.system.APIBuffer;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.DynamicLinkLibrary;
import org.lwjgl.system.FunctionProviderLocal;

import java.util.Set;
import java.util.StringTokenizer;

import static java.lang.Math.*;
import static org.lwjgl.opencl.CL10.*;
import static org.lwjgl.system.APIUtil.*;
import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.MemoryUtil.*;

public final class CL {

	private static FunctionProviderLocal functionProvider;

	private static CLCapabilities icd;

	static {
		if ( !Configuration.EXPLICIT_INIT_OPENCL.<Boolean>get() )
			create();
	}

	private CL() {}

	public static void create() {
		create(Configuration.LIBRARY_NAME_OPENCL.get("OpenCL"));
	}

	public static void create(final String libName) {
		final DynamicLinkLibrary OPENCL;

		{
			DynamicLinkLibrary dll;
			try {
				dll = LWJGLUtil.loadLibraryNative(libName);
			} catch (Throwable t) {
				if ( LWJGLUtil.getPlatform() == Platform.MACOSX )
					dll = apiCreateLibrary("/System/Library/Frameworks/OpenCL.framework");
				else
					throw new RuntimeException(t);
			}
			OPENCL = dll;
		}

		try {
			FunctionProviderLocal functionProvider = new FunctionProviderLocal.Default() {

				private final long clGetExtensionFunctionAddress;
				private final long clGetExtensionFunctionAddressForPlatform;

				// NULL if multiple platforms are available.
				private final long platform;

				{
					clGetExtensionFunctionAddress = OPENCL.getFunctionAddress("clGetExtensionFunctionAddress");
					clGetExtensionFunctionAddressForPlatform = OPENCL.getFunctionAddress("clGetExtensionFunctionAddressForPlatform");
					if ( clGetExtensionFunctionAddress == NULL && clGetExtensionFunctionAddressForPlatform == NULL ) {
						OPENCL.release();
						throw new OpenCLException("A core OpenCL function is missing. Make sure that OpenCL is available.");
					}

					/*
					We'll use clGetExtensionFunctionAddress, even if it has been deprecated, because clGetExtensionFunctionAddressForPlatform is pointless
					when the
					ICD is used. clGetExtensionFunctionAddressForPlatform will be used only if there is just 1 platform available and that platform supports
					OpenCL
					1.2 or higher.
					*/
					long platform = NULL;
					if ( clGetExtensionFunctionAddressForPlatform != NULL ) {
						long clGetPlatformIDs = OPENCL.getFunctionAddress("clGetPlatformIDs");
						if ( clGetPlatformIDs == NULL )
							throw new OpenCLException("A core OpenCL function is missing. Make sure that OpenCL is available.");

						APIBuffer __buffer = apiBuffer();
						callIPPI(clGetPlatformIDs, 0, NULL, __buffer.address());

						int platforms = __buffer.intValue(0);

						if ( platforms == 1 ) {
							callIPPI(clGetPlatformIDs, 1, __buffer.address(), NULL);
							long cl_platform_id = __buffer.pointerValue(0);
							if ( supportsOpenCL12(__buffer, cl_platform_id) )
								platform = cl_platform_id;
						} else if ( clGetExtensionFunctionAddress == NULL )
							throw new IllegalStateException();
					}
					this.platform = platform;
				}

				private boolean supportsOpenCL12(APIBuffer __buffer, long platform) {
					long clGetPlatformInfo = OPENCL.getFunctionAddress("clGetPlatformInfo");
					if ( clGetPlatformInfo == NULL )
						return false;

					int errcode = callPIPPPI(clGetPlatformInfo, platform, CL_PLATFORM_VERSION, 0, NULL, __buffer.address());
					if ( errcode != CL_SUCCESS )
						return false;

					long bytes = __buffer.pointerValue(0);
					__buffer.bufferParam((int)bytes);

					errcode = callPIPPPI(clGetPlatformInfo, platform, CL_PLATFORM_VERSION, bytes, __buffer.address(), NULL);
					if ( errcode != CL_SUCCESS )
						return false;

					APIVersion version = apiParseVersion(__buffer.stringValueASCII(0, (int)bytes - 1), "OpenCL");
					return 1 < version.major || 2 <= version.minor;
				}

				@Override
				public long getFunctionAddress(CharSequence functionName) {
					APIBuffer __buffer = apiBuffer();
					__buffer.stringParamASCII(functionName, true);

					long address = platform == NULL
						? callPP(clGetExtensionFunctionAddress, __buffer.address())
						: callPPP(clGetExtensionFunctionAddressForPlatform, platform, __buffer.address());

					if ( address == NULL )
						address = OPENCL.getFunctionAddress(functionName);

					return address;
				}

				@Override
				public long getFunctionAddress(long handle, CharSequence functionName) {
					APIBuffer __buffer = apiBuffer();
					__buffer.stringParamASCII(functionName, true);

					return callPPP(clGetExtensionFunctionAddressForPlatform, handle, __buffer.address());
				}

				@Override
				protected void destroy() {
					OPENCL.release();
				}
			};
			create(functionProvider);
		} catch (RuntimeException e) {
			OPENCL.release();
			throw e;
		}
	}

	/**
	 * Initializes OpenCL with the specified {@link FunctionProviderLocal}. This method can be used to implement custom OpenCL library loading.
	 *
	 * @param functionProvider the provider of OpenCL function addresses
	 */
	public static void create(FunctionProviderLocal functionProvider) {
		if ( CL.functionProvider != null )
			throw new IllegalStateException("OpenCL has already been created.");

		CL.functionProvider = functionProvider;

		icd = new CLCapabilities(functionProvider);
		if ( icd.__CL10 == null )
			throw new IllegalStateException("OpenCL 1.0 is missing. Make sure that OpenCL is available");
	}

	public static void destroy() {
		if ( functionProvider == null )
			return;

		functionProvider.release();
		functionProvider = null;
		icd = null;
	}

	public static FunctionProviderLocal getFunctionProvider() {
		return functionProvider;
	}

	public static CLCapabilities getICD() { return icd; }

	static void addExtensions(String extensionsString, Set<String> supportedExtensions) {
		StringTokenizer tokenizer = new StringTokenizer(extensionsString);
		while ( tokenizer.hasMoreTokens() )
			supportedExtensions.add(tokenizer.nextToken());
	}

	/** Must be called after addExtensions. */
	static void addCLVersions(int MAJOR, int MINOR, Set<String> supportedExtensions) {
		addCLVersions(
			MAJOR, MINOR, supportedExtensions, "",
			new int[][] {
				{ 0, 1, 2 },    // 10, 11, 12
				{ 0 },          // 20
			}
		);

		// Detect OpenGL interop
		if ( supportedExtensions.contains("cl_khr_gl_sharing") || supportedExtensions.contains("cl_APPLE_gl_sharing") )
			addCLVersions(
				MAJOR, MINOR, supportedExtensions, "GL",
				new int[][] {
					{ 0, 2 },    // 10GL, 12GL
					{}
				}
			);
	}

	private static void addCLVersions(int MAJOR, int MINOR, Set<String> supportedExtensions, String postfix, int[][] versions) {
		for ( int major = 1; major <= min(MAJOR, versions.length); major++ ) {
			for ( int minor : versions[major - 1] ) {
				if ( major == MAJOR && MINOR < minor )
					break;

				supportedExtensions.add(String.format("OpenCL%d%d%s", major, minor, postfix));
			}
		}
	}

	static <T> T checkExtension(Set<String> ext, String extension, T functions) {
		if ( ext.contains(extension) ) {
			if ( functions != null )
				return functions;

			LWJGLUtil.log("[CL] " + extension + " was reported as available but an entry point is missing.");
		}
		return null;
	}

}