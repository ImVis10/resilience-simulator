package cambio.simulator.entities.microservice;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import cambio.simulator.entities.networking.*;
import cambio.simulator.entities.patterns.*;
import cambio.simulator.export.MultiDataPointReporter;
import cambio.simulator.resources.cpu.CPU;
import cambio.simulator.resources.cpu.CPUProcess;
import cambio.simulator.resources.cpu.scheduling.FIFOScheduler;
import desmoj.core.simulator.*;

/**
 * A {@link MicroserviceInstance} (in the following just called instance) represents an actual, running instance of a
 * {@link Microservice}.
 *
 * <p>
 * An instance has responsibility to handle incoming requests. This is done by either:<br> 1. Sending an answer back to
 * the requester, if the request is completed <br> 2. Creating child requests for satisfying the dependencies of a
 * request<br> 3. Submitting the request to its {@link CPU} for handling of its computational demand.
 *
 * <p>
 * During its lifetime an instances is aware of all requests it currently handles and of all dependencies it is
 * currently waiting for.
 *
 * <p>
 * An instance can have different states, which are defined and described by the enum {@link InstanceState}.
 *
 * @author Lion Wagner
 * @see Microservice
 * @see InstanceState
 */
public class MicroserviceInstance extends RequestSender implements IRequestUpdateListener {

    private final Microservice owner;
    private final CPU cpu;
    private final int instanceID;
    //Queue with only unique entries
    private final Set<Request> currentRequestsToHandle = new HashSet<>();
    //Queue with only unique entries
    private final Set<ServiceDependencyInstance> currentlyOpenDependencies = new HashSet<>();
    //Contains all current outgoing answers
    private final Set<RequestAnswer> currentAnswers = new HashSet<>();
    //contains all current outgoing dependency requests
    private final Set<InternalRequest> currentInternalSends = new HashSet<>();
    private final MultiDataPointReporter reporter;
    //lists for debugging information
    private final List<ServiceDependencyInstance> closedDependencies = new LinkedList<>();
    private final List<ServiceDependencyInstance> abortedDependencies = new LinkedList<>();
    private InstanceState state;
    private Set<InstanceOwnedPattern> patterns = new HashSet<>();

    private long notComputed = 0;
    private long waiting = 0;


    /**
     * Creates a new {@link MicroserviceInstance} for the given {@link Microservice} parent.
     *
     * @param model        Base model of the simulation.
     * @param name         Name of the instance.
     * @param showInTrace  Whether the instance outputs should be shown in the trace.
     * @param microservice Parent microservice of the instance.
     * @param instanceID   ID of the instance.
     */
    public MicroserviceInstance(Model model, String name, boolean showInTrace, Microservice microservice,
                                int instanceID) {
        super(model, name, showInTrace);
        this.owner = microservice;
        this.instanceID = instanceID;
        this.cpu = new CPU(model, String.format("%s_CPU", name), showInTrace, microservice.getCapacity(),
            new FIFOScheduler("Scheduler"), this);

        String[] names = name.split("_");
        reporter = new MultiDataPointReporter(String.format("I[%s]_", name));

        changeState(InstanceState.CREATED);

        this.addUpdateListener(this);
    }

    /**
     * Activates the patterns that are owned by this instance.
     * This will call registers {@link IRequestUpdateListener}s on this instance and
     * call the {@link InstanceOwnedPattern#start()} method on each pattern instance.
     */
    public void activatePatterns(InstanceOwnedPatternConfiguration[] patterns) {
        this.patterns =
            Arrays.stream(patterns).map(patternData -> patternData.getPatternInstance(this)).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        this.patterns.stream().filter(pattern -> pattern instanceof IRequestUpdateListener)
            .map(pattern -> (IRequestUpdateListener) pattern).forEach(this::addUpdateListener);
        this.patterns.forEach(InstanceOwnedPattern::start);
    }

    /**
     * Gets the current usage of the instance.
     *
     * @see CPU#getCurrentUsage()
     */
    public double getUsage() {
        return this.cpu.getCurrentUsage();
    }

    /**
     * Gets the relative work demand.
     *
     * @return the relative work demand.
     * @see CPU#getCurrentRelativeWorkDemand()
     */
    public double getRelativeWorkDemand() {
        return this.cpu.getCurrentRelativeWorkDemand();
    }

    /**
     * Gets the state of the instance.
     */
    public InstanceState getState() {
        return state;
    }


    /**
     * Submits a {@link Request} at this instance for handling.
     *
     * @param request {@link Request} that should be handled by this instance.
     */
    public void handle(Request request) {
        Objects.requireNonNull(request);

        if (!checkIfCanHandle(request)) { //throw error if instance cannot handle the request
            throw new IllegalStateException(String.format("Cannot handle this Request. State: [%s]", this.state));
        }

        if (request instanceof RequestAnswer) {
            handleRequestAnswer((RequestAnswer) request);
        } else {
            handleIncomingRequest(request);
        }

        collectQueueStatistics(); //collecting Statistics
    }

    /**
     * Checks whether this Instance can handle the Request.
     *
     * @param request request that may should be handled by this instance.
     * @return true if this request will be handled, false otherwise
     */
    public boolean checkIfCanHandle(Request request) {
        //if the instance is running it can handle the request
        if ((state == InstanceState.RUNNING)) {
            return true;
        }

        //if the instance is shutting down but already received the request it can continue to finish it.
        // else the instance can't handle the instance
        return state == InstanceState.SHUTTING_DOWN && (currentRequestsToHandle.contains(request)
            || currentRequestsToHandle.contains(request.getParent()));
    }

    private void handleRequestAnswer(RequestAnswer answer) {
        Request answeredRequest = answer.unpack();

        if (!(answeredRequest instanceof InternalRequest)) {
            throw new IllegalArgumentException(
                String.format("Don't know how to handle a %s", answeredRequest.getClass().getSimpleName()));
        }

        InternalRequest request = (InternalRequest) answeredRequest;
        ServiceDependencyInstance dep = request.getDependency();


        if (!currentlyOpenDependencies.remove(dep) || !currentRequestsToHandle.contains(dep.getParentRequest())
            || request.getParent().getRelatedDependency(request) == null) {
            throw new IllegalStateException(
                "This Request is not handled by this Instance (anymore). " + "Maybe due to timeout.");
        } else if (getModel().debugIsOn()) {
            closedDependencies.add(dep);
        }

        Request parent = dep.getParentRequest();
        if (parent.notifyDependencyHasFinished(dep)) {
            this.handle(parent);
        }
    }

    private void handleIncomingRequest(Request request) {

        if (currentRequestsToHandle.add(request)) { //register request and stamp as received if not already known
            request.setHandler(this);
            notComputed++;
            waiting++;
        }

        //three possibilities:
        //1. request is completed -> send it back to its sender (target is retrieved by the SendEvent)
        //2. requests' dependencies were all received -> send it to the cpu for handling.
        //   The CPU will "send" it back to this method once it is done.
        //3. request does have dependencies -> create internal request
        if (request.isCompleted()) {
            notComputed--;
            RequestAnswer answer = new RequestAnswer(request, this);
            sendRequest("Request_Answer_" + request.getPlainName(), answer, request.getRequester());

            int size = currentRequestsToHandle.size();
            currentRequestsToHandle.remove(request);
            assert currentRequestsToHandle.size() == size - 1;

            //shutdown after the last answer was send. It doesn't care if the original sender does not live anymore
            if (currentRequestsToHandle.isEmpty() && getState() == InstanceState.SHUTTING_DOWN) {
                InstanceShutdownEndEvent event = new InstanceShutdownEndEvent(getModel(),
                    String.format("Instance %s Shutdown End", this.getQuotedName()), traceIsOn());
                event.schedule(this, presentTime());
            }

        } else if (request.getDependencies().isEmpty() || request.areDependenciesCompleted()) {
            waiting--;
            CPUProcess newProcess = new CPUProcess(request);
            submitProcessToCPU(newProcess);
        } else {
            for (ServiceDependencyInstance dependency : request.getDependencies()) {
                currentlyOpenDependencies.add(dependency);

                Request internalRequest = new InternalRequest(getModel(), this.traceIsOn(), dependency, this);
                sendRequest(String.format("Collecting dependency %s", dependency.getQuotedName()), internalRequest,
                    dependency.getTargetService());
                sendTraceNote(String.format("Try 1, send Request: %s ", internalRequest.getQuotedPlainName()));
            }
        }
    }

    protected void submitProcessToCPU(CPUProcess newProcess) {
        cpu.submitProcess(newProcess);
    }


    private void changeState(InstanceState targetState) {
        if (this.state == targetState) {
            return;
        }

        sendTraceNote(this.getQuotedName() + " changed to state " + targetState.name());
        reporter.addDatapoint("State", presentTime(), targetState.name());
        this.state = targetState;

    }

    /**
     * Starts this instance, reading it to receive requests.
     *
     * <p>
     * Currently, the startup process completes immediately.
     */
    public void start() {
        if (!(this.state == InstanceState.CREATED || this.state == InstanceState.SHUTDOWN)) {
            throw new IllegalStateException(
                String.format("Cannot start Instance %s: Was not recently created or Shutdown. (Current State [%s])",
                    this.getQuotedName(), state.name()));
        }

        changeState(InstanceState.STARTING);

        changeState(InstanceState.RUNNING);

    }

    /**
     * Starts the shutdown sequence of this instance. The service will not accept new requests, but will complete open
     * requests.
     */
    public final void startShutdown() {
        if (!(this.state == InstanceState.CREATED || this.state == InstanceState.RUNNING)) {
            throw new IllegalStateException(String.format(
                "Cannot shutdown Instance %s: Was not recently created or is  not running. (Current State [%s])",
                this.getQuotedName(), state.name()));
        }

        if (currentRequestsToHandle.isEmpty()) { //schedule immediate shutdown if currently there is nothing to do
            InstanceShutdownEndEvent shutDownEvent = new InstanceShutdownEndEvent(getModel(),
                String.format("Instance %s Shutdown End", this.getQuotedName()), traceIsOn());
            shutDownEvent.schedule(this, new TimeSpan(0));
        }

        changeState(InstanceState.SHUTTING_DOWN);
    }

    /**
     * Completes the shutdown and transitions the instance into the {@link InstanceState#SHUTDOWN} state. The instance
     * will not handle any requests in this state.
     */
    public final void endShutdown() {
        if (this.state != InstanceState.SHUTTING_DOWN) {
            throw new IllegalStateException(String.format(
                "Cannot shutdown Instance %s: This instance has not started its shutdown. (Current State [%s])",
                this.getQuotedName(), state.name()));
        }
        changeState(InstanceState.SHUTDOWN);
        patterns.forEach(InstanceOwnedPattern::shutdown);
    }

    /**
     * Immediately kills this instance. All currently active requests (computed and cascading) will be canceled.
     */
    public final void die() {
        if (this.state == InstanceState.KILLED) {
            throw new IllegalStateException(
                String.format("Cannot kill Instance %s: This instance was already killed. (Current State [%s])",
                    this.getQuotedName(), state.name()));
        }
        changeState(InstanceState.KILLED);


        patterns.forEach(InstanceOwnedPattern::shutdown);

        //clears all currently running calculations
        cpu.clear();

        //cancel all send answers and send current internal requests
        Stream.concat(currentAnswers.stream(), currentInternalSends.stream()).forEach(Request::cancelSending);

        //notify sender of currently handled requests, that the requests failed (TCP/behavior)
        currentRequestsToHandle.forEach(Request::cancelExecutionAtHandler);
    }

    public final Microservice getOwner() {
        return owner;
    }

    public final int getInstanceID() {
        return instanceID;
    }


    private void collectQueueStatistics() {
        reporter.addDatapoint("SendOff_Internal_Requests", presentTime(), currentlyOpenDependencies.size());
        reporter.addDatapoint("Requests_InSystem", presentTime(), currentRequestsToHandle.size());
        reporter.addDatapoint("Requests_NotComputed", presentTime(), notComputed);
        reporter.addDatapoint("Requests_WaitingForDependencies", presentTime(), waiting);
    }

    @Override
    public boolean onRequestFailed(final Request request, final TimeInstant when, final RequestFailedReason reason) {
        //specifically does not care about request answers failing.
        if (request instanceof RequestAnswer) {
            currentAnswers.remove(request);
            return true;
        }

        if (request instanceof InternalRequest) {
            currentInternalSends.remove(request);
        }


        if (reason == RequestFailedReason.CIRCUIT_IS_OPEN || reason == RequestFailedReason.REQUEST_VOLUME_REACHED) {
            if (patterns.stream().anyMatch(pattern -> pattern instanceof CircuitBreaker)) {
                //TODO: activate fallback behavior
                letRequestFail(request);
                return true;
            }
        }


        if (reason != RequestFailedReason.MAX_RETRIES_REACHED && patterns.stream()
            .anyMatch(pattern -> pattern instanceof Retry)) {
            return false;
        }

        try {
            letRequestFail(request);
        } catch (IllegalArgumentException e) {
            sendTraceNote("Could not cancel request " + request.getName() + ". Was this request canceled before?");
        }


        collectQueueStatistics(); //collecting Statistics
        return false;
    }

    @Override
    public boolean onRequestArrivalAtTarget(Request request, TimeInstant when) {
        if (request instanceof RequestAnswer) {
            currentAnswers.remove(request);
        } else if (request instanceof InternalRequest) {
            currentInternalSends.remove(request);
        }

        collectQueueStatistics(); //collecting
        return false;
    }

    @Override
    public boolean onRequestSend(Request request, TimeInstant when) {
        if (request instanceof RequestAnswer) {
            currentAnswers.add((RequestAnswer) request);
        } else if (request instanceof InternalRequest) {
            currentInternalSends.add((InternalRequest) request);
        }

        collectQueueStatistics(); //collecting Statistics
        return false;
    }

    @Override
    public boolean onRequestResultArrivedAtRequester(Request request, TimeInstant when) {
        if (request instanceof InternalRequest) {
            currentInternalSends.remove(request);
        }

        collectQueueStatistics(); //collecting Statistics
        return false;
    }


    private void letRequestFail(final Request requestToFail) {

        InternalRequest request = (InternalRequest) requestToFail;
        ServiceDependencyInstance failedDependency = request.getDependency();

        // this is true if the Dependency already has a new child request attached to it
        if (failedDependency.getChildRequest() != requestToFail) {
            requestToFail.cancel();
            return;
        }


        if (!currentlyOpenDependencies.contains(failedDependency) || !currentRequestsToHandle.contains(
            request.getParent())) {
            throw new IllegalArgumentException("The given request was not requested by this Instance.");
        }

        Request parentToCancel = request.getParent();

        //cancel parent
        NetworkRequestEvent cancelEvent =
            new NetworkRequestCanceledEvent(getModel(), "Canceling of request " + parentToCancel.getQuotedName(),
                traceIsOn(), parentToCancel, RequestFailedReason.DEPENDENCY_NOT_AVAILABLE,
                "Dependency " + request.getQuotedName());
        cancelEvent.schedule(presentTime());

        //cancel all other children of the parent
        parentToCancel.getDependencies().stream().map(ServiceDependencyInstance::getChildRequest)
            .filter(Schedulable::isScheduled).forEach(InternalRequest::cancel);

        if (getModel().debugIsOn()) {
            abortedDependencies.addAll(parentToCancel.getDependencies());
        }
        currentlyOpenDependencies.removeAll(parentToCancel.getDependencies());
        currentRequestsToHandle.remove(parentToCancel);
        waiting--;
        notComputed--;
    }

    public CPU getCpu() {
        return cpu;
    }

    public Set<InstanceOwnedPattern> getPatterns() {
        return patterns;
    }

    public void setState(InstanceState state) {
        this.state = state;
    }
}
