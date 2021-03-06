/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.server.operations;

import java.util.ArrayDeque;
import java.util.Deque;
import org.jboss.as.controller.BasicOperationResult;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationResult;
import org.jboss.as.controller.RuntimeOperationContext;
import org.jboss.as.controller.RuntimeTask;
import org.jboss.as.controller.RuntimeTaskContext;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.as.controller.ModelController;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.ResultHandler;
import org.jboss.as.controller.descriptions.DescriptionProvider;
import org.jboss.as.controller.operations.BaseCompositeOperationHandler;
import org.jboss.as.controller.registry.ModelNodeRegistration;
import org.jboss.as.server.BootOperationHandler;
import org.jboss.as.server.ServerController;
import org.jboss.as.server.ServerOperationContext;
import org.jboss.as.server.controller.descriptions.ServerRootDescription;
import org.jboss.dmr.ModelNode;

/**
 * Handler for multi-step operations that have to be performed atomically.
 *
 * @author Brian Stansberry
 */
public class ServerCompositeOperationHandler
    extends BaseCompositeOperationHandler
    implements DescriptionProvider {

    public static final String OPERATION_NAME = COMPOSITE;

    public static final ServerCompositeOperationHandler INSTANCE = new ServerCompositeOperationHandler();

    private ServerCompositeOperationHandler() {
    }

    @Override
    public ModelNode getModelDescription(Locale locale) {
        return ServerRootDescription.getCompositeOperationDescription(locale);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OperationResult execute(final OperationContext context, final ModelNode operation, final ResultHandler resultHandler) throws OperationFailedException {

        // The type of the context tells us whether we are allowed to make
        // runtime updates
        if (!(context.getRuntimeContext() != null)) {
            // The server's in model-only mode; just use the superclass logic
            return super.execute(context, operation, resultHandler);
        }

        try {
            final List<ModelNode> steps = operation.require(STEPS).asList();

            // We should not apply any updates to the runtime if any of them
            // will fail to apply against the model. So, to validate we
            // first apply the operations against a *copy* of the model
            // (and not against the runtime) and fail if any of them fail
            final ModelNode testResults = new ModelNode();
            final ModelNode testFailure = new ModelNode();
            validateAgainstModel(context, operation, testResults, testFailure);

            if (testFailure.isDefined()) {
                // Strip out any "result" from testResults, as they were not
                // set by the real "runtime" handler codepath
                for (int i = 0; i < steps.size(); i++) {
                    if (testResults.has(i) && testResults.get(i).has(RESULT)) {
                        testResults.get(i).remove(RESULT);
                    }
                }
                resultHandler.handleResultFragment(EMPTY, testResults);
                throw new OperationFailedException(testFailure);
            }
            else {
                // Operations tested out ok against model; now execute for real
                final ModelNode rorf = operation.get(ROLLBACK_ON_RUNTIME_FAILURE);
                final boolean rollback = !rorf.isDefined() || rorf.asBoolean();

                final RuntimeCompositeOperationContext compositeContext = new RuntimeCompositeOperationContext((ServerOperationContext)context, resultHandler, rollback);
                executeSteps(compositeContext, steps);
            }
        }
        catch (final Exception e) {
            throw new OperationFailedException(new ModelNode().set(e.toString()));
        }

        return new BasicOperationResult();
    }

    private void validateAgainstModel(final OperationContext context, final ModelNode operation, final ModelNode testResult, final ModelNode testFailure) throws OperationFailedException {

        // This is the key bit -- clone the "submodel" and then execute
        // the operations against the clone
        final ModelNode testModel = context.getSubModel().clone();
        final OperationContext testContext = new OperationContext() {

            @Override
            public ModelNode getSubModel() throws IllegalArgumentException {
                return testModel;
            }

            @Override
            public ModelNodeRegistration getRegistry() {
                return context.getRegistry();
            }

            @Override
            public ModelController getController() {
                return context.getController();
            }

            public RuntimeOperationContext getRuntimeContext() {
                return null;
            }
        };

        // All the following concurrency stuff is kind of silly, since the odds are 99.9%
        // that all use of this object will be in a single thread. But we code
        // for the multi-threaded case in case some handler does something weird
        // in its handling of model-only updates.
        final AtomicBoolean done = new AtomicBoolean(false);
        final ResultHandler testHandler = new ResultHandler() {

            @Override
            public void handleResultFragment(final String[] location, final ModelNode result) {
                synchronized (testResult) {
                    testResult.get(location).set(result);
                }
            }

            @Override
            public void handleResultComplete() {
                synchronized (done) {
                    done.set(true);
                }
            }

            @Override
            public void handleFailed(final ModelNode failureDescription) {
                synchronized (done) {
                    testFailure.set(failureDescription);
                    done.set(true);
                }
            }

            @Override
            public void handleCancellation() {
                synchronized (done) {
                    done.set(true);
                }
            }
        };

        super.execute(testContext, operation, testHandler);

        synchronized (done) {
            while (!done.get()) {
                try {
                    done.wait();
                } catch (final InterruptedException e) {
                    testFailure.set(e.getLocalizedMessage());
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    protected RuntimeTask getRuntimeTasks(final CompositeOperationContext context) {
        if(context instanceof RuntimeCompositeOperationContext) {
            final RuntimeCompositeOperationContext runtimeCompositeContext = RuntimeCompositeOperationContext.class.cast(context);
            if(!runtimeCompositeContext.runtimeTasks.isEmpty()) {
                return new RuntimeTask() {
                    public void execute(final RuntimeTaskContext context) throws OperationFailedException {
                        for(RuntimeTask runtimeTask : runtimeCompositeContext.runtimeTasks) {
                            runtimeTask.execute(context);
                        }
                    }
                };
            }
        }
        return null;
    }

    private static class RuntimeCompositeOperationContext extends CompositeOperationContext {

        private final boolean rollbackOnRuntimeFailure;
        private final ServerOperationContext overallRuntimeContext;
        private boolean modelOnly = false;
        private final Map<Integer, Boolean> modelOnlyStates = new HashMap<Integer, Boolean>();
        private Deque<RuntimeTask> runtimeTasks = new ArrayDeque<RuntimeTask>();

        private RuntimeCompositeOperationContext(final ServerOperationContext overallContext, final ResultHandler resultHandler,
                final boolean rollbackOnRuntimeFailure) {
            super(overallContext, resultHandler);
            this.overallRuntimeContext = overallContext;
            this.rollbackOnRuntimeFailure = rollbackOnRuntimeFailure;
        }

        @Override
        public OperationContext getStepOperationContext(final Integer index, final PathAddress address, final OperationHandler stepHandler) {
            modelOnlyStates.put(index, Boolean.valueOf(modelOnly));
            OperationContext stepOperationContext;
            if (modelOnly) {
                stepOperationContext = super.getStepOperationContext(index, address, stepHandler);
            }
            else if(stepHandler instanceof BootOperationHandler) {
                // The ModelController needs to be informed that it now shouldn't execute runtime ops
                overallRuntimeContext.restartRequired();
                modelOnly = true;
                modelOnlyStates.put(index, Boolean.TRUE);
                stepOperationContext = super.getStepOperationContext(index, address, stepHandler);
            }
            else {
                final ModelNode stepModel = getStepSubModel(address, stepHandler);
                stepOperationContext = getRuntimeOperationContext(stepModel);
            }
            return stepOperationContext;
        }

        private OperationContext getRuntimeOperationContext(final ModelNode stepModel) {
            return new StepRuntimeOperationContext(stepModel);
        }

        private class StepRuntimeOperationContext implements ServerOperationContext, RuntimeOperationContext {
            private ModelNode stepModel;

            private StepRuntimeOperationContext(ModelNode stepModel) {
                this.stepModel = stepModel;
            }

            @Override
            public ModelNode getSubModel() throws IllegalArgumentException {
                return stepModel;
            }

            @Override
            public ModelNodeRegistration getRegistry() {
                return overallRuntimeContext.getRegistry();
            }

            @Override
            public ServerController getController() {
                return overallRuntimeContext.getController();
            }

            @Override
            public void restartRequired() {
                overallRuntimeContext.restartRequired();
            }

            @Override
            public void revertRestartRequired() {
                overallRuntimeContext.revertRestartRequired();
            }

            public RuntimeOperationContext getRuntimeContext() {
                return this;
            }

            public void setRuntimeTask(RuntimeTask runtimeTask) {
                runtimeTasks.push(runtimeTask);
            }
        }

        @Override
        public void handleFailures(final StepHandlerContext stepHandlerContext, final ModelNode results) {
            if (rollbackOnRuntimeFailure) {
                super.handleFailures(stepHandlerContext, results);
            }
            else {

                final ModelNode compensatingOp = new ModelNode();
                compensatingOp.get(OP).set(COMPOSITE);
                compensatingOp.get(OP_ADDR).setEmptyList();
                final ModelNode compSteps = compensatingOp.get(STEPS);
                compSteps.setEmptyList();
                for (int i = results.asInt() - 1; i >= 0 ; i--) {
                    final ModelNode result = results.get(i);
                    result.get(SUCCESS).set(false);
                    if (!result.has(CANCELLED) || !result.get(CANCELLED).asBoolean()) {
                        // TODO double-check whether we do/don't include a compensating op for a failed op
                        final ModelNode compStep = stepHandlerContext.getCompensatingOperation(Integer.valueOf(i));
                        if (compStep != null && compStep.isDefined()) {
                            compSteps.add(compStep);
                        }
                    }
                }

                getResultHandler().handleResultFragment(EMPTY, results);

                getResultHandler().handleResultComplete(); //TODO:  Make sure we somehow handle the comp op
            }
        }

        @Override
        protected void handleRollback(final StepHandlerContext stepHandlerContext, final ModelNode results) {

            final CountDownLatch handlerLatch = new CountDownLatch(results.asInt());
            for (int i = results.asInt() - 1; i >= 0; i--) {
                final ModelNode result = results.get(i);
                if (!result.has(CANCELLED) || !result.get(CANCELLED).asBoolean()) {
                    // TODO double-check whether we do/don't include a compensating op for a failed op
                    final ModelNode compStep = stepHandlerContext.getCompensatingOperation(Integer.valueOf(i));
                    if (compStep == null) {
                        continue;
                    }
                    final PathAddress address = PathAddress.pathAddress(compStep.require(OP_ADDR));
                    final String operationName = compStep.require(OP).asString();
                    final OperationHandler stepHandler = getRegistry().getOperationHandler(address, operationName);
                    final boolean stepModelOnly = modelOnlyStates.get(i);
                    final OperationContext stepRollbackContext;
                    if (stepModelOnly) {
                        stepRollbackContext = super.getStepOperationContext(Integer.valueOf(i), address, stepHandler);
                    }
                    else {
                        if (this.modelOnly) {
                            // Controller needs to know that the change that put us in restart mode has been reverted
                            overallRuntimeContext.revertRestartRequired();
                        }
                        final ModelNode stepModel = getStepSubModel(address, stepHandler);
                        stepRollbackContext = getRuntimeOperationContext(stepModel);
                    }

                    final ResultHandler stepRollbackHandler = new ResultHandler() {

                        @Override
                        public void handleResultFragment(final String[] location, final ModelNode result) {
                            // ignore; we just store success or failure
                        }

                        @Override
                        public void handleResultComplete() {
                            result.get(ROLLED_BACK).set(true);
                            handlerLatch.countDown();
                        }

                        @Override
                        public void handleFailed(final ModelNode failureDescription) {
                            result.get(ROLLBACK_FAILURE).set(failureDescription);
                            handlerLatch.countDown();
                        }

                        @Override
                        public void handleCancellation() {
                            handleFailed(new ModelNode().set("Rollback cancelled"));
                        }
                    };
                    try {
                        stepHandler.execute(stepRollbackContext, compStep, stepRollbackHandler);
                    } catch (OperationFailedException e) {
                        // TODO:  This seems wrong.
                        stepRollbackHandler.handleFailed(e.getFailureDescription());
                    }
                }
                else {
                    handlerLatch.countDown();
                }
            }

            // Wait until all are completed
            try {
                handlerLatch.await();
            } catch (final InterruptedException e) {
                // Hmm. Treat as a rollback failure on anything that's not finished?
                for (final ModelNode result : results.asList()) {
                    if ((!result.has(CANCELLED) || !result.get(CANCELLED).asBoolean())
                            && !result.has(ROLLED_BACK)
                            && !result.has(ROLLBACK_FAILURE)) {
                        result.get(ROLLBACK_FAILURE).set("Interrrupted while awaiting completion of rollback");
                    }
                }
                Thread.currentThread().interrupt();
            }
        }

    }
}
