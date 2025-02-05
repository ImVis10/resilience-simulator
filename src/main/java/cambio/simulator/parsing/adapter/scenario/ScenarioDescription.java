package cambio.simulator.parsing.adapter.scenario;

import static cambio.simulator.misc.Util.injectField;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import cambio.simulator.entities.generator.LimboLoadGeneratorDescription;
import cambio.simulator.entities.generator.LoadGeneratorDescription;
import cambio.simulator.entities.microservice.Microservice;
import cambio.simulator.entities.microservice.Operation;
import cambio.simulator.events.*;
import cambio.simulator.misc.NameResolver;
import cambio.simulator.models.ExperimentModel;
import cambio.simulator.models.MiSimModel;
import cambio.simulator.parsing.ParsingException;
import com.google.gson.annotations.SerializedName;
import desmoj.core.simulator.TimeInstant;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a scenario description input. Can be parsed to a set, containing the experiment events such as {@link
 * ChaosMonkeyEvent}s or {@link LoadGeneratorDescription}s.
 *
 * @author Lion Wagner
 */
@SuppressWarnings("unused")
public final class ScenarioDescription {

    @SerializedName(value = "name", alternate = {"scenarioName", "experiment_name"})
    private String name;
    private String description;
    private String artifact;
    private String component;
    private String stimulus;
    private String source;
    private String environment;
    private String response;
    @SerializedName(value = "response_measure", alternate = {"response_measures"})
    private Map<String, String> responseMeasures;
    //    public Integer duration;


    /**
     * Checks state to the descriptions.
     *
     * <p>
     * Sends warnings if properties are empty.
     *
     * @throws ParsingException if artifact, component or stimulus are missing
     */
    private void checkState(MiSimModel model) {
        if (StringUtils.isEmpty(stimulus)
            || StringUtils.isEmpty(artifact)
            || StringUtils.isEmpty(component)
            || (StringUtils.isEmpty(name) && model.getExperimentMetaData().getExperimentName() == null)) {
            throw new ParsingException("Scenario is missing parts! (stimulus, artifact, component, name)");
        } else if (StringUtils.isBlank(environment)
            || StringUtils.isBlank(response)
            || StringUtils.isBlank(source)
            || responseMeasures == null) {
            System.out.printf("[Info] Scenario %s is missing some ATAM-components%n", name);
        }
    }

    /**
     * Convert this scenario description into a set of event objects.
     *
     * @return a set of objects that describe the scenario.
     */
    public ExperimentModel parse(MiSimModel model) {
        this.checkState(model);

        if (this.name == null) {
            this.name = model.getExperimentMetaData().getExperimentName();
        }

        Collection<ISelfScheduled> scheduables = new ArrayList<>();

        stimulus = stimulus.replaceAll("\\s+", " ");
        String[] stimuli = this.stimulus.split("AND");

        for (String stimulus : stimuli) {
            if (stimulus.startsWith("LOAD")) {
                parseWorkloads(scheduables, stimulus, model);
            } else {
                parseTimedFaultload(scheduables, stimulus, model);
            }
        }
        return ExperimentModel.fromScheduleEntities(model, scheduables);
    }


    private void parseWorkloads(Collection<ISelfScheduled> scheduables, String stimuli,
                                MiSimModel model) {

        boolean tmp = false;
        String profile = stimuli.replace("LOAD", "");
        if (profile.startsWith("~")) {
            tmp = true;
            profile = profile.replace("~", "");
        }
        final boolean repeating = tmp;
        final String path = profile.replace(" ", "");

        Microservice service = NameResolver.resolveMicroserviceName(model, artifact);

        if (service == null) {
            throw new ParsingException(String.format("Could not find target service '%s'", artifact));
        }

        final Consumer<String> componentResolverAndAdder = (final String component) -> {
            final Operation target = NameResolver.resolveOperationName(model, component);
            if (target == null) {
                throw new ParsingException(String.format("Could not find target operation '%s'", component));
            }
            scheduables.add(createLimboGenerator(path, target, repeating));
        };

        if (this.component.equals("ALL ENDPOINTS")) {
            for (Operation operation : service.getOperations()) {
                scheduables.add(createLimboGenerator(path, operation, repeating));
            }
        } else if (component.contains(",")) {
            String[] components = component.split(",");
            for (String component : components) {
                componentResolverAndAdder.accept(component);
            }
        } else {
            componentResolverAndAdder.accept(component);
        }
    }

    @Contract("_, _ -> new")
    private @NotNull LoadGeneratorDescription createLimboGenerator(String profileLocation, Operation operation,
                                                                   boolean reapeating) {
        LoadGeneratorDescription description = new LimboLoadGeneratorDescription();
        injectField("modelFile", description, new File(profileLocation.trim()));
        injectField("targetOperation", description, operation);
        injectField("repeating", description, reapeating);
        description.initializeArrivalRateModel();
        return description;
    }

    //response and response measure are ignored for now

    private void parseTimedFaultload(Collection<ISelfScheduled> scheduables, String currentStimulus, MiSimModel model) {

        Pattern p = Pattern.compile("@([0-9]*)");
        double targetTime;

        Matcher m = p.matcher(currentStimulus);
        if (m.find()) {
            targetTime = Double.parseDouble(m.group(1));
        } else {
            throw new ParsingException("Missing Time Specification (@...)");
        }
        currentStimulus = currentStimulus.replaceFirst("@([0-9]*)", "").trim();

        Microservice service = NameResolver.resolveMicroserviceName(model, artifact);

        if (service == null) {
            throw new ParsingException(String.format("Could not find target service '%s'", artifact));
        }

        String[] stimuliArray = currentStimulus.split(" ");

        int instances = Integer.MAX_VALUE;

        if (stimuliArray.length == 2) {
            try {
                instances = Integer.parseInt(stimuliArray[1]);
            } catch (NumberFormatException e) {
                service = NameResolver.resolveMicroserviceName(model, stimuliArray[1]);
            }
        } else if (stimuliArray.length == 3) {
            service = NameResolver.resolveMicroserviceName(model, stimuliArray[1]);
            instances = Integer.parseInt(stimuliArray[2]);
        } else if (stimuliArray.length != 1) {
            throw new ParsingException("Command was not defined correctly (KILL/START/RESTART [<service_name>] "
                + "[<#instances>]@<target_time>)");
        }

        if (currentStimulus.startsWith("KILL")) {
            scheduables.add(
                new ChaosMonkeyEvent(model, "Chaosmonkey", true, service, instances) {
                    {
                        setTargetTime(new TimeInstant(targetTime));
                    }
                }
            );
        } else if (currentStimulus.startsWith("RESTART") || currentStimulus.startsWith("START")) {
            scheduables.add(
                new SummonerMonkeyEvent(model, "Summoner", true, service, instances) {
                    {
                        setTargetTime(new TimeInstant(targetTime));
                    }
                }
            );
        } else if (currentStimulus.startsWith("DELAY")) {
            Matcher delayFinder = Pattern.compile("([0-9]*\\.[0-9]+)(\\+-([0-9]*\\.[0-9]+))?").matcher(currentStimulus);

            double baseDelay;
            double deviationDelay = 0;
            if (delayFinder.find()) {
                baseDelay = Double.parseDouble(delayFinder.group(1));
                if (currentStimulus.contains("+-")) {
                    try {
                        deviationDelay = Double.parseDouble(delayFinder.group(3));
                    } catch (ArrayIndexOutOfBoundsException ignored) {
                        throw new ParsingException(
                            String.format("Could not parse standard deviation of '%s'", currentStimulus));
                    }
                }
            } else {
                throw new ParsingException("Missing delay specification.");
            }

            double duration;
            Matcher durationFinder = Pattern.compile("~([0-9]+)").matcher(currentStimulus);
            if (durationFinder.find()) {
                duration = Double.parseDouble(durationFinder.group(1));
            } else {
                throw new ParsingException("Missing duration specification.");
            }


            scheduables.add(
                new DelayInjection(model, "LatencyMonkey", true, baseDelay, deviationDelay, service, null,
                    null) {
                    {
                        setTargetTime(new TimeInstant(targetTime));
                        setDuration(duration);
                    }
                }
            );
        }
    }
}
