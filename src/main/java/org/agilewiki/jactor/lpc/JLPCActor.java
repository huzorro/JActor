/*
 * Copyright 2011 Bill La Forge
 *
 * This file is part of AgileWiki and is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License (LGPL) as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This code is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 * or navigate to the following url http://www.gnu.org/licenses/lgpl-2.1.txt
 *
 * Note however that only Scala, Java and JavaScript files are being covered by LGPL.
 * All other files are covered by the Common Public License (CPL).
 * A copy of this license is also included and can be
 * found as well at http://www.opensource.org/licenses/cpl1.0.txt
 */
package org.agilewiki.jactor.lpc;

import org.agilewiki.jactor.*;
import org.agilewiki.jactor.apc.*;
import org.agilewiki.jactor.bufferedEvents.BufferedEventsDestination;
import org.agilewiki.jactor.bufferedEvents.BufferedEventsQueue;
import org.agilewiki.jactor.stateMachine.ExtendedResponseProcessor;
import org.agilewiki.jactor.stateMachine._SMBuilder;

/**
 * <p>
 * An actor which implements Local Procedure Calls (LPC)
 * and mostly works synchronously.
 * Actors need to implement the processRequest method.
 * </p>
 * <pre>
 * public class Multiply {
 *     public int a;
 *     public int b;
 * }
 *
 * public class Multiplier extends JLPCActor {
 *
 *     public Multiplier(Mailbox mailbox) {
 *         super(mailbox);
 *     }
 *
 *     protected void processRequest(Object req, ResponseProcessor rp)
 *             throws Exception {
 *         Multiply m = (Multiply) req;
 *         rp.process(new Integer(m.a * m.b));
 *     }
 * }
 * </pre>
 */
abstract public class JLPCActor implements Actor {

    /**
     * The inbox and outbox of the actor.
     */
    private Mailbox mailbox;

    /**
     * Handles callbacks from the mailbox.
     */
    final private APCRequestProcessor requestProcessor = new APCRequestProcessor() {
        private ExceptionHandler exceptionHandler;

        @Override
        final public ExceptionHandler getExceptionHandler() {
            return exceptionHandler;
        }

        @Override
        final public void setExceptionHandler(final ExceptionHandler exceptionHandler) {
            this.exceptionHandler = exceptionHandler;
        }

        @Override
        final public void haveEvents() {
            mailbox.dispatchEvents();
        }

        @Override
        final public void processRequest(final JARequest request) throws Exception {
            JLPCActor.this.processRequest(request.getUnwrappedRequest(), new ResponseProcessor() {
                @Override
                public void process(Object unwrappedResponse) {
                    mailbox.response(unwrappedResponse);
                }
            });
        }
    };

    /**
     * Serves as the originator of requests sent to other actors.
     */
    final private RequestSource requestSource = new RequestSource() {
        @Override
        final public Mailbox getMailbox() {
            return mailbox;
        }

        @Override
        final public void responseFrom(final BufferedEventsQueue<JAMessage> eventQueue,
                                       final JAResponse japcResponse) {
            eventQueue.send(mailbox, japcResponse);
        }

        @Override
        final public void send(final BufferedEventsDestination<JAMessage> destination,
                               final JARequest japcRequest) {
            mailbox.send(destination, japcRequest);
        }

        @Override
        final public ExceptionHandler getExceptionHandler() {
            return requestProcessor.getExceptionHandler();
        }

        @Override
        final public void setExceptionHandler(final ExceptionHandler exceptionHandler) {
            requestProcessor.setExceptionHandler(exceptionHandler);
        }
    };

    /**
     * Create a JLPCActor
     *
     * @param mailbox A mailbox which may be shared with other actors.
     */
    public JLPCActor(final Mailbox mailbox) {
        if (mailbox == null) throw new IllegalArgumentException("mailbox may not be null");
        this.mailbox = mailbox;
    }

    /**
     * Returns the actor's mailbox.
     *
     * @return The actor's mailbox.
     */
    public Mailbox getMailbox() {
        return mailbox;
    }

    /**
     * Set the initial capacity for buffered outgoing messages.
     *
     * @param initialBufferCapacity The initial capacity for buffered outgoing messages.
     */
    @Override
    final public void setInitialBufferCapacity(final int initialBufferCapacity) {
        mailbox.setInitialBufferCapacity(initialBufferCapacity);
    }

    /**
     * Wraps and enqueues an unwrapped request in the requester's inbox.
     *
     * @param apcRequestSource The originator of the request.
     * @param request          The request to be sent.
     * @param rp               The request processor.
     * @throws Exception Any uncaught exceptions raised while processing the request.
     */
    @Override
    public void acceptRequest(final APCRequestSource apcRequestSource,
                              final Object request,
                              final ResponseProcessor rp)
            throws Exception {
        final RequestSource rs = (RequestSource) apcRequestSource;
        final Mailbox sourceMailbox = rs.getMailbox();
        final ExceptionHandler sourceExceptionHandler = rs.getExceptionHandler();
        if (sourceMailbox == mailbox) {
            syncProcess(request, rp, sourceExceptionHandler, rs);
            return;
        }
        if (mailbox.isAsync() || sourceMailbox == null) {
            asyncSend(rs, request, rp, sourceExceptionHandler);
            return;
        }
        final Mailbox srcControllingMailbox = sourceMailbox.getControllingMailbox();
        if (mailbox.getControllingMailbox() == srcControllingMailbox) {
            syncSend(rs, request, rp, sourceExceptionHandler);
            return;
        }
        if (!mailbox.acquireControl(srcControllingMailbox)) {
            asyncSend(rs, request, rp, sourceExceptionHandler);
            return;
        }
        try {
            syncSend(rs, request, rp, sourceExceptionHandler);
        } finally {
            mailbox.sendPendingMessages();
            mailbox.relinquishControl();
            mailbox.dispatchRemaining(srcControllingMailbox);
        }
    }

    /**
     * Process an exception when the response is asynchronous.
     *
     * @param ex            Any exceptions thrown while processing the request or response.
     * @param eh            The exception handler
     * @param sourceMailbox The mailbox of the source actor.
     */
    final private void asyncException(Exception ex, ExceptionHandler eh, Mailbox sourceMailbox) {
        if (eh == null) sourceMailbox.response(ex);
        else try {
            eh.process(ex);
        } catch (Exception ex2) {
            sourceMailbox.response(ex2);
        }
    }

    /**
     * Process a request asynchronously.
     *
     * @param rs                     The source of the request.
     * @param request                The request.
     * @param rp                     Processes the response.
     * @param sourceExceptionHandler Exception handler of the source actor.
     */
    final private void asyncSend(final RequestSource rs,
                                 final Object request,
                                 final ResponseProcessor rp,
                                 final ExceptionHandler sourceExceptionHandler) {
        final JARequest jaRequest = new JARequest(
                rs,
                requestProcessor,
                request,
                new ResponseProcessor() {
                    @Override
                    public void process(Object response) throws Exception {
                        rs.setExceptionHandler(sourceExceptionHandler);
                        if (response != null && response instanceof Exception) {
                            asyncException(
                                    (Exception) response,
                                    sourceExceptionHandler,
                                    rs.getMailbox());
                        } else try {
                            rp.process(response);
                        } catch (Exception ex) {
                            asyncException(ex, sourceExceptionHandler, rs.getMailbox());
                        }
                    }
                });
        rs.send(mailbox, jaRequest);
    }

    /**
     * Process a request when the mailbox is shared.
     *
     * @param request                The request.
     * @param rp                     Processes the response.
     * @param sourceExceptionHandler Exception handler of the source actor.
     * @param requestSource          The source of the request.
     * @throws Exception Any uncaught exceptions raised while processing the request.
     */
    final private void syncProcess(final Object request,
                                   final ResponseProcessor rp,
                                   final ExceptionHandler sourceExceptionHandler,
                                   final RequestSource requestSource)
            throws Exception {
        try {
            processRequest(request, new ResponseProcessor() {
                @Override
                public void process(Object response) throws Exception {
                    try {
                        rp.process(response);
                    } catch (Exception e) {
                        throw new TransparentException(e);
                    } finally {
                    }
                }
            });
        } catch (TransparentException t) {
            final Exception e = (Exception) t.getCause();
            requestSource.setExceptionHandler(sourceExceptionHandler);
            throw e;
        } catch (Exception e) {
            requestSource.setExceptionHandler(sourceExceptionHandler);
            if (sourceExceptionHandler == null) throw e;
            sourceExceptionHandler.process(e);
        }
        requestSource.setExceptionHandler(sourceExceptionHandler);
    }

    /**
     * Process a request from another mailbox synchronously.
     *
     * @param rs                     The source of the request.
     * @param request                The request.
     * @param rp                     Processes the response.
     * @param sourceExceptionHandler Exception handler of the source actor.
     */
    final private void syncSend(final RequestSource rs,
                                final Object request,
                                final ResponseProcessor rp,
                                final ExceptionHandler sourceExceptionHandler)
            throws Exception {
        final ExtendedResponseProcessor erp = new ExtendedResponseProcessor() {
            @Override
            public void process(final Object response)
                    throws Exception {
                requestSource.setExceptionHandler(sourceExceptionHandler);
                if (!async) {
                    sync = true;
                    try {
                        rp.process(response);
                    } catch (Exception e) {
                        throw new TransparentException(e);
                    }
                } else {
                    if (response != null && response instanceof Exception)
                        asyncException((Exception) response, sourceExceptionHandler, rs.getMailbox());
                    else try {
                        Mailbox sourceMailbox = rs.getMailbox();
                        Mailbox srcControllingMailbox = sourceMailbox.getControllingMailbox();
                        Mailbox controllingMailbox = mailbox.getControllingMailbox();
                        if (srcControllingMailbox == controllingMailbox) {
                            rp.process(response);
                        } else if (sourceMailbox.isAsync()) {
                            asyncResponse(rs, request, response, rp);
                        } else if (!mailbox.acquireControl(srcControllingMailbox)) {
                            asyncResponse(rs, request, response, rp);
                        } else {
                            try {
                                rp.process(response);
                            } finally {
                                mailbox.sendPendingMessages();
                                mailbox.relinquishControl();
                                mailbox.dispatchRemaining(srcControllingMailbox);
                            }
                        }
                    } catch (Exception ex) {
                        asyncException(ex, sourceExceptionHandler, rs.getMailbox());
                    }
                }
            }
        };
        try {
            processRequest(request, erp);
            if (!erp.sync) erp.async = true;
        } catch (TransparentException t) {
            final Exception e = (Exception) t.getCause();
            throw e;
        } catch (Exception e) {
            final ExceptionHandler eh = getExceptionHandler();
            if (eh == null) throw e;
            eh.process(e);
        }
    }

    /**
     * Respond asynchronously to a synchronous request.
     *
     * @param rs       The source of the request.
     * @param request  The request.
     * @param response The response.
     * @param rp       Processes the response.
     */
    final private void asyncResponse(RequestSource rs,
                                     Object request,
                                     Object response,
                                     ResponseProcessor rp) {
        final JARequest jaRequest = new JARequest(
                rs,
                requestProcessor,
                request,
                rp);
        mailbox.setCurrentRequest(jaRequest);
        mailbox.response(response);
    }

    /**
     * Send a request to another actor.
     *
     * @param actor   The target actor.
     * @param request The request.
     * @param rp      The response processor.
     * @throws Exception Any uncaught exceptions raised while processing the request.
     */
    final protected void send(final Actor actor,
                              final Object request,
                              final ResponseProcessor rp)
            throws Exception {
        actor.acceptRequest(requestSource, request, rp);
    }

    /**
     * Creates a _SMBuilder.
     */
    public class SMBuilder extends _SMBuilder {
        @Override
        final public void send(Actor actor, Object request, ResponseProcessor rp)
                throws Exception {
            JLPCActor.this.send(actor, request, rp);
        }
    }

    /**
     * Returns the mailbox factory.
     *
     * @return The mailbox factory.
     */
    final protected MailboxFactory getMailboxFactory() {
        return mailbox.getMailboxFactory();
    }

    /**
     * Returns the exception handler.
     *
     * @return The exception handler.
     */
    final protected ExceptionHandler getExceptionHandler() {
        return requestProcessor.getExceptionHandler();
    }

    /**
     * Assign an exception handler.
     *
     * @param exceptionHandler The exception handler.
     */
    final protected void setExceptionHandler(final ExceptionHandler exceptionHandler) {
        requestProcessor.setExceptionHandler(exceptionHandler);
    }

    /**
     * The application method for processing requests sent to the actor.
     *
     * @param request A request.
     * @param rp      The response processor.
     * @throws Exception Any uncaught exceptions raised while processing the request.
     */
    abstract protected void processRequest(Object request, ResponseProcessor rp)
            throws Exception;

    /**
     * Returns true when the concurrent data of the actor, or its parent, contains the named data item.
     *
     * @param name The key for the data item.
     * @return True when the concurrent data of the actor, or its parent, contains the named data item.
     */
    @Override
    public boolean hasDataItem(String name) {
        return false;
    }
}
