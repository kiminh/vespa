// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.expressiontransforms;

import com.google.common.base.Joiner;
import com.yahoo.config.application.api.ApplicationPackage;
import com.yahoo.io.IOUtils;
import com.yahoo.path.Path;
import com.yahoo.searchdefinition.RankProfile;
import com.yahoo.searchdefinition.RankingConstant;
import com.yahoo.searchlib.rankingexpression.RankingExpression;
import com.yahoo.searchlib.rankingexpression.evaluation.TensorValue;
import com.yahoo.searchlib.rankingexpression.integration.tensorflow.TensorFlowImporter;
import com.yahoo.searchlib.rankingexpression.integration.tensorflow.TensorFlowModel;
import com.yahoo.searchlib.rankingexpression.integration.tensorflow.TensorFlowModel.Signature;
import com.yahoo.searchlib.rankingexpression.parser.ParseException;
import com.yahoo.searchlib.rankingexpression.rule.Arguments;
import com.yahoo.searchlib.rankingexpression.rule.CompositeNode;
import com.yahoo.searchlib.rankingexpression.rule.ConstantNode;
import com.yahoo.searchlib.rankingexpression.rule.ExpressionNode;
import com.yahoo.searchlib.rankingexpression.rule.ReferenceNode;
import com.yahoo.searchlib.rankingexpression.transform.ExpressionTransformer;
import com.yahoo.tensor.Tensor;
import com.yahoo.tensor.serialization.TypedBinaryFormat;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Replaces instances of the tensorflow(model-path, signature, output)
 * pseudofeature with the native Vespa ranking expression implementing
 * the same computation.
 *
 * @author bratseth
 */
// TODO: - Verify types of macros
//       - Avoid name conflicts across models for constants
public class TensorFlowFeatureConverter extends ExpressionTransformer<RankProfileTransformContext> {

    // TODO: Make system test work with this set to true, then remove the "true" path
    private static final boolean constantsInConfig = false;

    private final TensorFlowImporter tensorFlowImporter = new TensorFlowImporter();

    /** A cache of imported models indexed by model path. This avoids importing the same model multiple times. */
    private final Map<Path, TensorFlowModel> importedModels = new HashMap<>();

    @Override
    public ExpressionNode transform(ExpressionNode node, RankProfileTransformContext context) {
        if (node instanceof ReferenceNode)
            return transformFeature((ReferenceNode) node, context);
        else if (node instanceof CompositeNode)
            return super.transformChildren((CompositeNode) node, context);
        else
            return node;
    }

    private ExpressionNode transformFeature(ReferenceNode feature, RankProfileTransformContext context) {
        if ( ! feature.getName().equals("tensorflow")) return feature;

        try {
            ModelStore store = new ModelStore(context.rankProfile().getSearch().sourceApplication(),
                                                   feature.getArguments());
            if (store.hasTensorFlowModels())
                return transformFromTensorFlowModel(store, context.rankProfile());
            else // is should have previously stored model information instead
                return store.readConverted().getRoot();
        }
        catch (IllegalArgumentException | UncheckedIOException e) {
            throw new IllegalArgumentException("Could not use tensorflow model from " + feature, e);
        }
    }

    private ExpressionNode transformFromTensorFlowModel(ModelStore store, RankProfile rankProfile) {
        TensorFlowModel model = importedModels.computeIfAbsent(store.arguments().modelPath(),
                                                               k -> tensorFlowImporter.importModel(store.tensorFlowModelDir()));

        // Find the specified expression
        Signature signature = chooseSignature(model, store.arguments().signature());
        String output = chooseOutput(signature, store.arguments().output());
        RankingExpression expression = model.expressions().get(output);
        store.writeConverted(expression);

        // Add all constants (after finding outputs to fail faster when the output is not found) TODO: Remove the first path
        if (constantsInConfig)
            model.constants().forEach((k, v) -> rankProfile.addConstantTensor(k, new TensorValue(v)));
        else // correct way, disabled for now
            model.constants().forEach((k, v) -> transformConstant(store, rankProfile, k, v));

        return expression.getRoot();
    }

    /**
     * Returns the specified, existing signature, or the only signature if none is specified.
     * Throws IllegalArgumentException in all other cases.
     */
    private Signature chooseSignature(TensorFlowModel importResult, Optional<String> signatureName) {
        if ( ! signatureName.isPresent()) {
            if (importResult.signatures().size() == 0)
                throw new IllegalArgumentException("No signatures are available");
            if (importResult.signatures().size() > 1)
                throw new IllegalArgumentException("Model has multiple signatures (" +
                                                   Joiner.on(", ").join(importResult.signatures().keySet()) +
                                                   "), one must be specified " +
                                                   "as a second argument to tensorflow()");
            return importResult.signatures().values().stream().findFirst().get();
        }
        else {
            Signature signature = importResult.signatures().get(signatureName.get());
            if (signature == null)
                throw new IllegalArgumentException("Model does not have the specified signature '" +
                                                   signatureName.get() + "'");
            return signature;
        }
    }

    /**
     * Returns the specified, existing output expression, or the only output expression if no output name is specified.
     * Throws IllegalArgumentException in all other cases.
     */
    private String chooseOutput(Signature signature, Optional<String> outputName) {
        if ( ! outputName.isPresent()) {
            if (signature.outputs().size() == 0)
                throw new IllegalArgumentException("No outputs are available" + skippedOutputsDescription(signature));
            if (signature.outputs().size() > 1)
                throw new IllegalArgumentException(signature + " has multiple outputs (" +
                                                   Joiner.on(", ").join(signature.outputs().keySet()) +
                                                   "), one must be specified " +
                                                   "as a third argument to tensorflow()");
            return signature.outputs().get(signature.outputs().keySet().stream().findFirst().get());
        }
        else {
            String output = signature.outputs().get(outputName.get());
            if (output == null) {
                if (signature.skippedOutputs().containsKey(outputName.get()))
                    throw new IllegalArgumentException("Could not use output '" + outputName.get() + "': " +
                                                       signature.skippedOutputs().get(outputName.get()));
                else
                    throw new IllegalArgumentException("Model does not have the specified output '" +
                                                       outputName.get() + "'");
            }
            return output;
        }
    }

    private void transformConstant(ModelStore store, RankProfile profile, String constantName, Tensor constantValue) {
        if (profile.getSearch().getRankingConstants().containsKey(constantName)) return;

        Path constantPath = store.writeConstant(constantName, constantValue);
        profile.getSearch().addRankingConstant(new RankingConstant(constantName, constantValue.type(),
                                                                   constantPath.toString()));
    }

    private String skippedOutputsDescription(TensorFlowModel.Signature signature) {
        if (signature.skippedOutputs().isEmpty()) return "";
        StringBuilder b = new StringBuilder(": ");
        signature.skippedOutputs().forEach((k, v) -> b.append("Skipping output '").append(k).append("': ").append(v));
        return b.toString();
    }

    /**
     * Provides read/write access to the correct directories of the application package given by the feature arguments
     */
    private static class ModelStore {

        private final ApplicationPackage application;
        private final FeatureArguments arguments;

        public ModelStore(ApplicationPackage application, Arguments arguments) {
            this.application = application;
            this.arguments = new FeatureArguments(arguments);
        }

        public FeatureArguments arguments() { return arguments; }

        public boolean hasTensorFlowModels() {
            try {
                return application.getFileReference(ApplicationPackage.MODELS_DIR).exists();
            }
            catch (UnsupportedOperationException e) {
                return false; // No files -> no TensorFlow models
            }
        }

        /**
         * Returns the directory which (if hasTensorFlowModels is true)
         * contains the source model to use for these arguments
         */
        public File tensorFlowModelDir() {
            return application.getFileReference(ApplicationPackage.MODELS_DIR.append(arguments.modelPath()));
        }

        /**
         * Adds this expression to the application package, such that it can be read later.
         */
        public void writeConverted(RankingExpression expression) {
            try {
                // We don't really need to store this as a file - we could keep it in memory in the application
                // package until we write it to ZooKeeper. However, we need to write constants to the models_generated
                // directory in any case (as they are distributed over file distribution),
                // so we just reuse the same mechanism for expressions
                Path expressionsPath = ApplicationPackage.MODELS_GENERATED_DIR
                                       .append(arguments.modelPath)
                                       .append("expressions");
                createIfNeeded(expressionsPath);
                IOUtils.writeFile(application.getFileReference(expressionsPath.append(arguments.expressionFileName())),
                                  expression.getRoot().toString(), false);
            }
            catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /** Reads the previously stored ranking expression for these arguments */
        public RankingExpression readConverted() {
            Path expressionPath = ApplicationPackage.MODELS_GENERATED_DIR
                                  .append(arguments.modelPath)
                                  .append("expressions")
                                  .append(arguments.expressionFileName());
            try {
                return new RankingExpression(application.getFile(expressionPath).createReader());
            }
            catch (IOException e) {
                throw new UncheckedIOException("Could not read " + expressionPath, e);
            }
            catch (ParseException e) {
                throw new IllegalStateException("Could not parse " + expressionPath, e);
            }
        }

        /**
         * Adds this constant to the application package as a file,
         * such that it can be distributed using file distribution.
         *
         * @return the path to the stored constant, relative to the application package root
         */
        public Path writeConstant(String name, Tensor constant) {
            Path constantsPath = ApplicationPackage.MODELS_GENERATED_DIR.append(arguments.modelPath).append("constants");
            createIfNeeded(constantsPath);

            // "tbf" ending for "typed binary format" - recognized by the nodes receiving the file:
            Path constantPath = constantsPath.append(name + ".tbf");
            IOUtils.writeFile(application.getFileReference(constantPath), TypedBinaryFormat.encode(constant));
            return constantPath;
        }

        private void createIfNeeded(Path path) {
            File dir = application.getFileReference(path);
            if ( ! dir.exists()) {
                if (!dir.mkdirs())
                    throw new IllegalStateException("Could not create " + dir);
            }
        }

    }

    /** Encapsulates the 1, 2 or 3 arguments to a tensorflow feature */
    private static class FeatureArguments {

        private final Path modelPath;

        /** Optional arguments */
        private final Optional<String> signature, output;

        public FeatureArguments(Arguments arguments) {
            if (arguments.isEmpty())
                throw new IllegalArgumentException("A tensorflow node must take an argument pointing to " +
                                                   "the tensorflow model directory under [application]/models");
            if (arguments.expressions().size() > 3)
                throw new IllegalArgumentException("A tensorflow feature can have at most 3 arguments");

            modelPath = Path.fromString(asString(arguments.expressions().get(0)));
            signature = optionalArgument(1, arguments);
            output = optionalArgument(2, arguments);
        }

        /** Returns relative path to this model below the "models/" dir in the application package */
        public Path modelPath() { return modelPath; }
        public Optional<String> signature() { return signature; }
        public Optional<String> output() { return output; }

        public String expressionFileName() {
            StringBuilder fileName = new StringBuilder();
            signature.ifPresent(s -> fileName.append(s).append("."));
            output.ifPresent(s -> fileName.append(s).append("."));
            if (fileName.length() == 0) // single signature and output
                fileName.append("single.");
            fileName.append("expression");
            return fileName.toString();
        }

        private Optional<String> optionalArgument(int argumentIndex, Arguments arguments) {
            if (argumentIndex >= arguments.expressions().size())
                return Optional.empty();
            return Optional.of(asString(arguments.expressions().get(argumentIndex)));
        }

        private String asString(ExpressionNode node) {
            if ( ! (node instanceof ConstantNode))
                throw new IllegalArgumentException("Expected a constant string as tensorflow argument, but got '" + node);
            return stripQuotes(((ConstantNode)node).sourceString());
        }

        private String stripQuotes(String s) {
            if ( ! isQuoteSign(s.codePointAt(0))) return s;
            if ( ! isQuoteSign(s.codePointAt(s.length() - 1 )))
                throw new IllegalArgumentException("tensorflow argument [" + s + "] is missing endquote");
            return s.substring(1, s.length()-1);
        }

        private boolean isQuoteSign(int c) {
            return c == '\'' || c == '"';
        }

    }

}
