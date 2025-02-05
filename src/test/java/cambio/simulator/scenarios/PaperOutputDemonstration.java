package cambio.simulator.scenarios;

import java.io.File;

import cambio.simulator.test.FileLoaderUtil;
import cambio.simulator.test.TestBase;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Executes the scenario and experiment for the running example of our paper.
 *
 * @author Lion Wagner
 */
public class PaperOutputDemonstration extends TestBase {

    @Test
    void MinimalScenario() {
        File scenario = FileLoaderUtil.loadFromExampleResources("PaperExample/paper_scenario.json");
        File architecture = FileLoaderUtil.loadFromExampleResources("PaperExample/paper_architecture.json");
        runSimulationCheckExitTempOutput(0, architecture, scenario);
    }

    @Test
    void MinimalExperiment() {
        File experiment = FileLoaderUtil.loadFromExampleResources("PaperExample/paper_experiment.json");
        File architecture = FileLoaderUtil.loadFromExampleResources("PaperExample/paper_architecture.json");
        runSimulationCheckExitTempOutput(0, architecture, experiment);
    }
}
