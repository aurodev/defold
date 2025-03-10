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

import org.apache.commons.io.FilenameUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.dynamo.bob.Bob;
import com.dynamo.bob.Builder;
import com.dynamo.bob.BuilderParams;
import com.dynamo.bob.CompileExceptionError;
import com.dynamo.bob.Task;
import com.dynamo.bob.Task.TaskBuilder;
import com.dynamo.bob.fs.IResource;

import com.dynamo.bob.pipeline.ShaderPreprocessor;
import com.dynamo.bob.pipeline.ShaderUtil.Common;
import com.dynamo.bob.pipeline.ShaderUtil.VariantTextureArrayFallback;
import com.dynamo.bob.pipeline.ShaderUtil.ES2ToES3Converter;
import com.dynamo.graphics.proto.Graphics.ShaderDesc;
import com.dynamo.render.proto.Material.MaterialDesc;
import com.dynamo.bob.util.MurmurHash;

@BuilderParams(name = "Material", inExts = {".material"}, outExt = ".materialc")
public class MaterialBuilder extends Builder<Void>  {

    private static final String TextureArrayFilenameVariantFormat = "_max_pages_%d.%s";

    private class ShaderProgramBuildContext {
        public String                       buildPath;
        public String                       projectPath;
        public IResource                    resource;
        public ES2ToES3Converter.ShaderType type;
        public ShaderDesc                   desc;

        // Variant specific state
        public boolean hasVertexArrayVariant;
        public String[] arraySamplers = new String[0];
    }

    private ShaderDesc getShaderDesc(IResource resource, ShaderProgramBuilder builder, ES2ToES3Converter.ShaderType shaderType) throws IOException, CompileExceptionError {
        builder.setProject(this.project);
        Task<ShaderPreprocessor> task = builder.create(resource);
        return builder.getCompiledShaderDesc(task, shaderType);
    }

    private ShaderDesc.Language findTextureArrayShaderLanguage(ShaderDesc shaderDesc) {
        ShaderDesc.Language selected = null;
        for (int i=0; i < shaderDesc.getShadersCount(); i++) {
            ShaderDesc.Shader shader = shaderDesc.getShaders(i);
            if (VariantTextureArrayFallback.isRequired(shader.getLanguage())) {
                assert(selected == null);
                selected = shader.getLanguage();
            }
        }

        return selected;
    }

    private void applyVariantTextureArray(MaterialDesc.Builder materialBuilder, ShaderProgramBuildContext ctx, String inExt, String outExt) throws IOException, CompileExceptionError {

        ShaderDesc.Language shaderLanguage = findTextureArrayShaderLanguage(ctx.desc);
        if (shaderLanguage == null) {
            return;
        }

        String shaderInputSource = new String(ctx.resource.getContent());

        int maxPageCount = materialBuilder.getMaxPageCount();

        // Taken from ShaderProgramBuilder.java
        boolean isDebug = (this.project.hasOption("debug") || (this.project.option("variant", Bob.VARIANT_RELEASE) != Bob.VARIANT_RELEASE));
        Common.GLSLCompileResult variantCompileResult = ShaderProgramBuilder.buildGLSLVariantTextureArray(shaderInputSource, ctx.type, shaderLanguage, isDebug, maxPageCount);

        // No array samplers, we can use original source
        if (variantCompileResult.arraySamplers.length == 0) {
            return;
        }

        ShaderProgramBuilder.ShaderBuildResult variantBuildResult = ShaderProgramBuilder.makeShaderBuilderFromGLSLSource(variantCompileResult.source, shaderLanguage);

        // JG: AAaah this should not be here, but we need to know of the parsed array samplers for building the indirection map..
        variantBuildResult.shaderBuilder.setVariantTextureArray(true);

        if (variantBuildResult.buildWarnings != null) {
            for(String warningStr : variantBuildResult.buildWarnings) {
                System.err.println(warningStr);
            }
            throw new CompileExceptionError("Errors when producing texture array variant output " + ctx.projectPath);
        }

        IResource variantResource = ctx.resource.changeExt(String.format(TextureArrayFilenameVariantFormat, maxPageCount, outExt));

        // Transfer already built shaders to this variant shader
        ShaderDesc.Builder variantShaderDescBuilder = ShaderDesc.newBuilder();
        variantShaderDescBuilder.addAllShaders(ctx.desc.getShadersList());
        variantShaderDescBuilder.addShaders(variantBuildResult.shaderBuilder);
        variantResource.setContent(variantShaderDescBuilder.build().toByteArray());

        ctx.buildPath             = variantResource.getPath();
        ctx.projectPath           = BuilderUtil.replaceExt(ctx.projectPath, "." + inExt, String.format(TextureArrayFilenameVariantFormat, maxPageCount, inExt));
        ctx.arraySamplers         = variantCompileResult.arraySamplers;
        ctx.hasVertexArrayVariant = true;
    }

    private ShaderProgramBuildContext makeShaderProgramBuildContext(MaterialDesc.Builder materialBuilder, String shaderResourcePath) throws CompileExceptionError, IOException {
        IResource shaderResource = this.project.getResource(shaderResourcePath);
        String shaderFileInExt   = FilenameUtils.getExtension(shaderResourcePath);
        String shaderFileOutExt  = shaderFileInExt + "c";

        ES2ToES3Converter.ShaderType shaderType;
        ShaderProgramBuilder shaderBuilder;

        if (shaderFileInExt.equals("vp")) {
            shaderType    = ES2ToES3Converter.ShaderType.VERTEX_SHADER;
            shaderBuilder = new VertexProgramBuilder();
        } else {
            shaderType    = ES2ToES3Converter.ShaderType.FRAGMENT_SHADER;
            shaderBuilder = new FragmentProgramBuilder();
        }

        ShaderDesc shaderDesc = getShaderDesc(shaderResource, shaderBuilder, shaderType);

        ShaderProgramBuildContext ctx = new ShaderProgramBuildContext();
        ctx.buildPath   = shaderResourcePath;
        ctx.projectPath = shaderResourcePath;
        ctx.resource    = shaderResource;
        ctx.type        = shaderType;
        ctx.desc        = shaderDesc;

        applyVariantTextureArray(materialBuilder, ctx, shaderFileInExt, shaderFileOutExt);

        return ctx;
    }

    private void applyShaderProgramBuildContexts(MaterialDesc.Builder materialBuilder, ShaderProgramBuildContext vertexBuildContext, ShaderProgramBuildContext fragmentBuildContext) {
        if (!vertexBuildContext.hasVertexArrayVariant || fragmentBuildContext.hasVertexArrayVariant) {
            return;
        }

        // Generate indirection table for all material samplers based on the result of the shader build context
        Set mergedArraySamplers = new HashSet();
        mergedArraySamplers.addAll(Arrays.asList(vertexBuildContext.arraySamplers));
        mergedArraySamplers.addAll(Arrays.asList(fragmentBuildContext.arraySamplers));

        for (int i=0; i < materialBuilder.getSamplersCount(); i++) {
            MaterialDesc.Sampler materialSampler = materialBuilder.getSamplers(i);

            if (mergedArraySamplers.contains(materialSampler.getName())) {
                MaterialDesc.Sampler.Builder samplerBuilder = MaterialDesc.Sampler.newBuilder(materialSampler);

                for (int j = 0; j < materialBuilder.getMaxPageCount(); j++) {
                    samplerBuilder.addNameIndirections(MurmurHash.hash64(VariantTextureArrayFallback.samplerNameToSliceSamplerName(materialSampler.getName(), j)));
                }

                materialBuilder.setSamplers(i, samplerBuilder.build());
            }
        }

        // This value is not needed in the engine specifically for validation in bob
        // I.e we need to set this to zero to check if the combination of material and atlas is correct.
        if (mergedArraySamplers.size() == 0) {
            materialBuilder.setMaxPageCount(0);
        }
    }

    @Override
    public Task<Void> create(IResource input) throws IOException, CompileExceptionError {
        TaskBuilder<Void> task = Task.<Void> newBuilder(this)
                .setName(params.name())
                .addInput(input)
                .addOutput(input.changeExt(params.outExt()));

        MaterialDesc.Builder materialBuilder = MaterialDesc.newBuilder();
        ProtoUtil.merge(input, materialBuilder);

        IResource vertexProgramOutputResource   = this.project.getResource(materialBuilder.getVertexProgram()).changeExt(".vpc");
        IResource fragmentProgramOutputResource = this.project.getResource(materialBuilder.getFragmentProgram()).changeExt(".fpc");

        task.addInput(vertexProgramOutputResource);
        task.addInput(fragmentProgramOutputResource);

        return task.build();
    }

    @Override
    public void build(Task<Void> task) throws CompileExceptionError, IOException {
        IResource res                        = task.input(0);
        MaterialDesc.Builder materialBuilder = MaterialDesc.newBuilder();
        ProtoUtil.merge(task.input(0), materialBuilder);

        ShaderProgramBuildContext vertexBuildContext   = makeShaderProgramBuildContext(materialBuilder, materialBuilder.getVertexProgram());
        ShaderProgramBuildContext fragmentBuildContext = makeShaderProgramBuildContext(materialBuilder, materialBuilder.getFragmentProgram());

        applyShaderProgramBuildContexts(materialBuilder, vertexBuildContext, fragmentBuildContext);

        BuilderUtil.checkResource(this.project, res, "vertex program", vertexBuildContext.buildPath);
        materialBuilder.setVertexProgram(BuilderUtil.replaceExt(vertexBuildContext.projectPath, ".vp", ".vpc"));

        BuilderUtil.checkResource(this.project, res, "fragment program", fragmentBuildContext.buildPath);
        materialBuilder.setFragmentProgram(BuilderUtil.replaceExt(fragmentBuildContext.projectPath, ".fp", ".fpc"));

        MaterialDesc materialDesc = materialBuilder.build();
        task.output(0).setContent(materialDesc.toByteArray());
    }
}
