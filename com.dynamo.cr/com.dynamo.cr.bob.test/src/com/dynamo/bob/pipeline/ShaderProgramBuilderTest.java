// Copyright 2020-2023 The Defold Foundation
// Copyright 2014-2020 King
// Copyright 2009-2014 Ragnar Svensson, Christian Murray
// Licensed under the Defold License version 1.0 (the "License"); you may not use
// this file except in compliance with the License.
// 
// You may obtain a copy of the License, together with FAQs at
// https://www.defold.com/license
// 
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.dynamo.bob.pipeline;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.util.List;

import org.junit.Before;
import org.junit.Test;

import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.Platform;
import com.dynamo.graphics.proto.Graphics.ShaderDesc;
import com.google.protobuf.Message;

public class ShaderProgramBuilderTest extends AbstractProtoBuilderTest {

    @Before
    public void setup() {
        addTestFiles();
    }

    private final String vp =
            "attribute vec4 position; \n" +
            "varying vec4 fragColor; \n" +
            "uniform vec4 color; \n" +
            "void main(){ \n" +
            "fragColor = color;\n" +
            "gl_Position = position; \n" +
            "}\n";

    private final String fp =
            "varying vec4 fragColor; \n" +
            "void main(){ \n" +
            "gl_FragColor = fragColor; \n" +
            "}\n";

    private void doTest(boolean expectSpirv) throws Exception {
        // Test GL vp
        List<Message> outputs = build("/test_shader.vp", vp);
        ShaderDesc shader = (ShaderDesc)outputs.get(0);
        assertNotNull(shader.getShaders(0).getSource());
        assertEquals(ShaderDesc.Language.LANGUAGE_GLSL_SM140, shader.getShaders(0).getLanguage());
        switch(Platform.getHostPlatform())
        {
            case X86_64MacOS:
            case X86_64Linux:
            case X86_64Win32:
            if (expectSpirv)
            {
                assertEquals(2, shader.getShadersCount());
                assertNotNull(shader.getShaders(1).getSource());
                assertEquals(ShaderDesc.Language.LANGUAGE_SPIRV, shader.getShaders(1).getLanguage());
                break;
            }
            default:
                assertEquals(1, shader.getShadersCount());
        }

        // Test GL fp
        outputs = build("/test_shader.fp", fp);
        shader = (ShaderDesc)outputs.get(0);
        assert(shader.getShaders(0).getLanguage() == ShaderDesc.Language.LANGUAGE_GLSL_SM140);
        assertNotNull(shader.getShaders(0).getSource());
        assertEquals(ShaderDesc.Language.LANGUAGE_GLSL_SM140, shader.getShaders(0).getLanguage());
        switch(Platform.getHostPlatform())
        {
            case X86_64MacOS:
            case X86_64Linux:
            case X86_64Win32:
            if (expectSpirv)
            {
                assertEquals(2, shader.getShadersCount());
                assertNotNull(shader.getShaders(1).getSource());
                assertEquals(ShaderDesc.Language.LANGUAGE_SPIRV, shader.getShaders(1).getLanguage());
                break;
            }
            default:
                assertEquals(1, shader.getShadersCount());
        }

        // Test GLES vp
        outputs = build("/test_shader.vp", "#version 310 es\n" + vp);
        shader = (ShaderDesc)outputs.get(0);
        switch(Platform.getHostPlatform())
        {
            case X86_64MacOS:
            case X86_64Linux:
            case X86_64Win32:
            if (expectSpirv)
            {
                assertEquals(2, shader.getShadersCount());
                assertNotNull(shader.getShaders(1).getSource());
                assertEquals(ShaderDesc.Language.LANGUAGE_SPIRV, shader.getShaders(1).getLanguage());
                break;
            }
            default:
                assertEquals(1, shader.getShadersCount());
        }

        // Test GLES fp
        outputs = build("/test_shader.fp", "#version 310 es\n" + fp);
        shader = (ShaderDesc)outputs.get(0);
        switch(Platform.getHostPlatform())
        {
            case X86_64MacOS:
            case X86_64Linux:
            case X86_64Win32:
            if (expectSpirv)
            {
                assertEquals(2, shader.getShadersCount());
                assertNotNull(shader.getShaders(1).getSource());
                assertEquals(ShaderDesc.Language.LANGUAGE_SPIRV, shader.getShaders(1).getLanguage());
                break;
            }
            default:
                assertEquals(1, shader.getShadersCount());
        }
    }

    @Test
    public void testShaderPrograms() throws Exception {
        doTest(false);
        GetProject().getProjectProperties().putBooleanValue("shader", "output_spirv", true);
        doTest(true);
    }

    private void testOutput(String expected, String source) {
        if (!expected.equals(source)) {
            System.err.printf("EXPECTED:\n'%s'\n", expected);
            System.err.printf("SOURCE:\n'%s'\n", source);
        }
        assertEquals(expected, source);
    }

    @Test
    public void testIncludes() throws Exception {
        String shader_base =
            "#include \"%s\"\n" +
            "void main(){\n" +
            "   gl_FragColor = vec4(1.0);\n" +
            "}\n";

        String expected_base = "#version 140\n" +
                               "\n" +
                               "\n" +
                               "#ifndef GL_ES\n" +
                               "#define lowp\n" +
                               "#define mediump\n" +
                               "#define highp\n" +
                               "#endif\n" +
                               "\n" +
                               "out vec4 _DMENGINE_GENERATED_gl_FragColor_0;\n" +
                               "\n" +
                               "%s" +
                               "\n" +
                               "void main(){\n" +
                               "   _DMENGINE_GENERATED_gl_FragColor_0 = vec4(1.0);\n" +
                               "}\n";

        // Test include a valid shader from the same folder
        {
            List<Message> outputs = build("/test_glsl_same_folder.fp", String.format(shader_base, "glsl_same_folder.glsl"));
            ShaderDesc shader     = (ShaderDesc)outputs.get(0);
            testOutput(String.format(expected_base,
                "const float same_folder = 0.0;\n"),
                shader.getShaders(0).getSource().toStringUtf8());
        }

        // Test include a valid shader from a subfolder
        {
            List<Message> outputs = build("/test_glsl_sub_folder_includes.fp", String.format(shader_base, "shader_includes/glsl_sub_include.glsl"));
            ShaderDesc shader     = (ShaderDesc)outputs.get(0);
            testOutput(String.format(expected_base,
                "const float sub_include = 0.0;\n"),
                shader.getShaders(0).getSource().toStringUtf8());
        }

        // Test include a valid shader from a subfolder that includes other files
        {
            List<Message> outputs = build("/test_glsl_sub_folder_multiple_includes.fp", String.format(shader_base, "shader_includes/glsl_sub_include_multi.glsl"));
            ShaderDesc shader     = (ShaderDesc)outputs.get(0);
            testOutput(String.format(expected_base,
                "const float sub_include = 0.0;\n" +
                "\n" +
                "const float sub_include_from_multi = 0.0;\n"),
                shader.getShaders(0).getSource().toStringUtf8());
        }

        // Test wrong path
        {
            boolean didFail = false;
            try {
                List<Message> outputs = build("/test_glsl_missing.fp", String.format(shader_base, "path-doesnt-exist.glsl"));
            } catch (CompileExceptionError e) {
                didFail = true;
            }
            assertTrue(didFail);
        }

        // Test path outside of project
        {
            boolean didFail = false;
            try {
                List<Message> outputs = build("/test_glsl_outside_of_project.fp", String.format(shader_base, "../path-doesnt-exist.glsl"));
            } catch (CompileExceptionError e) {
                didFail = true;
                assertTrue(e.getMessage().contains("includes file from outside of project root"));
            }
            assertTrue(didFail);
        }

        // Test self include
        {
            boolean didFail = false;
            try {
                List<Message> outputs = build("/test_glsl_self_include.fp", String.format(shader_base, "shader_includes/glsl_self_include.glsl"));
            } catch (CompileExceptionError e) {
                didFail = true;
            }
            assertTrue(didFail);
        }

        // Test cyclic include
        {
            boolean didFail = false;
            try {
                List<Message> outputs = build("/test_glsl_cyclic_include.fp", String.format(shader_base, "shader_includes/glsl_cyclic_include.glsl"));
            } catch (CompileExceptionError e) {
                didFail = true;
            }
            assertTrue(didFail);
        }
    }

    @Test
    public void testGlslDirectives() throws Exception {
        String source;
        String expected;

        source = ShaderProgramBuilder.compileGLSL("", ShaderUtil.ES2ToES3Converter.ShaderType.VERTEX_SHADER, ShaderDesc.Language.LANGUAGE_GLSL_SM140, true);
        expected =  "#version 140\n" +
                    "#ifndef GL_ES\n" +
                    "#define lowp\n" +
                    "#define mediump\n" +
                    "#define highp\n" +
                    "#endif\n" +
                    "\n" +
                    "#line 0\n";
        testOutput(expected, source);

        source = "#extension GL_OES_standard_derivatives : enable\n" +
                 "varying highp vec2 var_texcoord0;";
        source = ShaderProgramBuilder.compileGLSL(source, ShaderUtil.ES2ToES3Converter.ShaderType.VERTEX_SHADER, ShaderDesc.Language.LANGUAGE_GLSL_SM140, true);
        expected =  "#version 140\n" +
                    "#extension GL_OES_standard_derivatives : enable\n" +
                    "\n" +
                    "#ifndef GL_ES\n" +
                    "#define lowp\n" +
                    "#define mediump\n" +
                    "#define highp\n" +
                    "#endif\n" +
                    "\n" +
                    "#line 1\n" +
                    "out highp vec2 var_texcoord0;\n";
        testOutput(expected, source);

        source = "#extension GL_OES_standard_derivatives : enable\n" +
                 "void main() {\n" +
                 "    gl_FragColor = vec4(1.0);\n" +
                 "}";
        source = ShaderProgramBuilder.compileGLSL(source, ShaderUtil.ES2ToES3Converter.ShaderType.FRAGMENT_SHADER, ShaderDesc.Language.LANGUAGE_GLES_SM100, true);
        expected =  "#extension GL_OES_standard_derivatives : enable\n" +
                    "\n" +
                    "precision mediump float;\n" +
                    "#line 1\n" +
                    "void main() {\n" +
                    "    gl_FragColor = vec4(1.0);\n" +
                    "}\n";
        testOutput(expected, source);

        source = "#extension GL_OES_standard_derivatives : enable\n" +
                 "void main() {\n" +
                 "    gl_FragColor = vec4(1.0);\n" +
                 "}";
        source = ShaderProgramBuilder.compileGLSL(source, ShaderUtil.ES2ToES3Converter.ShaderType.FRAGMENT_SHADER, ShaderDesc.Language.LANGUAGE_GLES_SM300, true);
        expected =  "#version 300 es\n" +
                    "#extension GL_OES_standard_derivatives : enable\n" +
                    "precision mediump float;\n" +
                    "\n" +
                    "out vec4 _DMENGINE_GENERATED_gl_FragColor_0;\n" +
                    "\n" +
                    "#line 1\n" +
                    "void main() {\n" +
                    "    _DMENGINE_GENERATED_gl_FragColor_0 = vec4(1.0);\n" +
                    "}\n";
        testOutput(expected, source);

    }
}
