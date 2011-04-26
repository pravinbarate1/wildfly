/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package org.jboss.as.domain.controller.operations.deployment;

import org.jboss.as.controller.BasicOperationResult;
import org.jboss.as.controller.HashUtil;
import org.jboss.as.controller.ModelAddOperationHandler;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationResult;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.ResultHandler;
import org.jboss.as.controller.descriptions.DescriptionProvider;
import org.jboss.as.controller.descriptions.common.DeploymentDescription;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.controller.operations.validation.AbstractParameterValidator;
import org.jboss.as.controller.operations.validation.ListValidator;
import org.jboss.as.controller.operations.validation.ModelTypeValidator;
import org.jboss.as.controller.operations.validation.ParametersOfValidator;
import org.jboss.as.controller.operations.validation.ParametersValidator;
import org.jboss.as.controller.operations.validation.StringLengthValidator;
import org.jboss.as.protocol.StreamUtils;
import org.jboss.as.server.deployment.api.ContentRepository;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ARCHIVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.BYTES;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CONTENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HASH;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.INPUT_STREAM_INDEX;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PATH;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RELATIVE_TO;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RUNTIME_NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.URL;
import static org.jboss.as.controller.operations.validation.ChainedParameterValidator.chain;

/**
 * Handles addition of a deployment to the model.
 *
 * @author Brian Stansberry (c) 2011 Red Hat Inc.
 */
public class DeploymentAddHandler implements ModelAddOperationHandler, DescriptionProvider {

    public static final String OPERATION_NAME = ADD;

    static final ModelNode getOperation(ModelNode address, ModelNode state) {
        ModelNode op = Util.getEmptyOperation(OPERATION_NAME, address);
        op.get(RUNTIME_NAME).set(state.get(RUNTIME_NAME));
        op.get(HASH).set(state.get(HASH));
        return op;
    }

    private static final List<String> CONTENT_ADDITION_PARAMETERS = Arrays.asList(INPUT_STREAM_INDEX, BYTES, URL);

    private final ContentRepository contentRepository;
    private final boolean isMaster;

    private final ParametersValidator validator = new ParametersValidator();

    public DeploymentAddHandler(final ContentRepository contentRepository, final boolean isMaster) {
        this.contentRepository = contentRepository;
        this.validator.registerValidator(RUNTIME_NAME, new StringLengthValidator(1, Integer.MAX_VALUE, true, false));
        final ParametersValidator contentValidator = new ParametersValidator();
        // existing managed content
        contentValidator.registerValidator(HASH, new ModelTypeValidator(ModelType.BYTES, true));
        // existing unmanaged content
        contentValidator.registerValidator(ARCHIVE, new ModelTypeValidator(ModelType.BOOLEAN, true));
        contentValidator.registerValidator(PATH, new ModelTypeValidator(ModelType.STRING, true));
        contentValidator.registerValidator(RELATIVE_TO, new ModelTypeValidator(ModelType.STRING, true));
        // content additions
        contentValidator.registerValidator(INPUT_STREAM_INDEX, new ModelTypeValidator(ModelType.INT, true));
        contentValidator.registerValidator(BYTES, new ModelTypeValidator(ModelType.BYTES, true));
        contentValidator.registerValidator(URL, new StringLengthValidator(1, true));
        this.validator.registerValidator(CONTENT, chain(new ListValidator(new ParametersOfValidator(contentValidator)),
                new AbstractParameterValidator() {
                    @Override
                    public void validateParameter(String parameterName, ModelNode value) throws OperationFailedException {
                        validateOnePieceOfContent(value);
                    }
                }));
        this.isMaster = isMaster;
    }

    @Override
    public ModelNode getModelDescription(Locale locale) {
        return DeploymentDescription.getAddDeploymentOperation(locale, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OperationResult execute(OperationContext context, ModelNode operation, ResultHandler resultHandler) throws OperationFailedException {

        validator.validate(operation);

        ModelNode opAddr = operation.get(OP_ADDR);
        PathAddress address = PathAddress.pathAddress(opAddr);
        String name = address.getLastElement().getValue();
        String runtimeName = operation.hasDefined(RUNTIME_NAME) ? operation.get(RUNTIME_NAME).asString() : name;

        byte[] hash;
        // clone it, so we can modify it to our own content
        final ModelNode content = operation.require(CONTENT).clone();
        // TODO: JBAS-9020: for the moment overlays are not supported, so there is a single content item
        final ModelNode contentItemNode = content.require(0);
        if (contentItemNode.hasDefined(HASH)) {
            hash = contentItemNode.require(HASH).asBytes();
            if (!contentRepository.hasContent(hash))
                throw createFailureException("No deployment content with hash %s is available in the deployment content repository.", HashUtil.bytesToHexString(hash));
        } else if (hasValidContentAdditionParameterDefined(contentItemNode)) {
            if (!isMaster) {
                // This is a slave DC. We can't handle this operation; it should have been fixed up on the master DC
                throw createFailureException("A slave domain controller cannot accept deployment content uploads");
            }

            InputStream in = getInputStream(context, contentItemNode);
            try {
                try {
                    hash = contentRepository.addContent(in);
                } catch (IOException e) {
                    throw createFailureException(e.toString());
                }

            } finally {
                StreamUtils.safeClose(in);
            }
            contentItemNode.get(HASH).set(hash);
        } else {
            // TODO: handle unmanaged content, the user is responsible for replication
//            final String path = contentItemNode.require(PATH).asString();
//            final String relativeTo = asString(contentItemNode, RELATIVE_TO);
//            final boolean archive = contentItemNode.require(ARCHIVE).asBoolean();
//            contentItem = new DeploymentHandlerUtil.ContentItem(path, relativeTo, archive);
            throw createFailureException("A domain controller cannot accept unmanaged content");
        }

        if (!isMaster || contentRepository.hasContent(hash)) {
            ModelNode subModel = context.getSubModel();
            subModel.get(NAME).set(name);
            subModel.get(RUNTIME_NAME).set(runtimeName);
            subModel.get(CONTENT).set(content);
        }
        else {
            throw createFailureException("No deployment content with hash %s is available in the deployment content repository.", HashUtil.bytesToHexString(hash));
        }

        resultHandler.handleResultComplete();
        return new BasicOperationResult(Util.getResourceRemoveOperation(operation.get(OP_ADDR)));
    }

    private static InputStream getInputStream(OperationContext context, ModelNode operation) throws OperationFailedException {
        InputStream in = null;
        String message = "";
        if (operation.hasDefined(INPUT_STREAM_INDEX)) {
            int streamIndex = operation.get(INPUT_STREAM_INDEX).asInt();
            if (streamIndex > context.getInputStreams().size() - 1) {
                IllegalArgumentException e = new IllegalArgumentException("Invalid " + INPUT_STREAM_INDEX + "=" + streamIndex + ", the maximum index is " + (context.getInputStreams().size() - 1));
                throw createFailureException(e, e.getMessage());
            }
            message = "Null stream at index " + streamIndex;
            in = context.getInputStreams().get(streamIndex);
        } else if (operation.hasDefined(BYTES)) {
            message = "Invalid byte stream.";
            in = new ByteArrayInputStream(operation.get(BYTES).asBytes());
        } else if (operation.hasDefined(URL)) {
            final String urlSpec = operation.get(URL).asString();
            try {
                message = "Invalid url stream.";
                in = new URL(urlSpec).openStream();
            } catch (MalformedURLException e) {
                throw createFailureException(message);
            } catch (IOException e) {
                throw createFailureException(message);
            }
        }
        if (in == null) {
            throw createFailureException(message);
        }
        return in;
    }

    /**
     * Checks to see if a valid deployment parameter has been defined.
     *
     * @param operation the operation to check.
     *
     * @return {@code true} of the parameter is valid, otherwise {@code false}.
     */
    private static boolean hasValidContentAdditionParameterDefined(ModelNode operation) {
        for (String s : CONTENT_ADDITION_PARAMETERS) {
            if (operation.hasDefined(s)) {
                return true;
            }
        }
        return false;
    }

    private static OperationFailedException createFailureException(String format, Object... params) {
        return createFailureException(String.format(format, params));
    }

    private static OperationFailedException createFailureException(Throwable cause, String format, Object... params) {
        return createFailureException(cause, String.format(format, params));
    }

    private static OperationFailedException createFailureException(String msg) {
        return new OperationFailedException(new ModelNode().set(msg));
    }

    private static OperationFailedException createFailureException(Throwable cause, String msg) {
        return new OperationFailedException(cause, new ModelNode().set(msg));
    }

    private static void validateOnePieceOfContent(final ModelNode content) throws OperationFailedException {
        // TODO: implement overlays
        if (content.asList().size() != 1)
            throw createFailureException("Only 1 piece of content is current supported (AS7-431");
    }
}
