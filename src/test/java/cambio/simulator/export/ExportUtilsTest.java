package cambio.simulator.export;

import java.io.File;
import java.io.IOException;

import cambio.simulator.models.MiSimModel;
import cambio.simulator.test.FileLoaderUtil;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.Test;

class ExportUtilsTest {

    @Test
    void prepareReportFolder() throws IOException {

        File test_architecture = FileLoaderUtil.loadFromTestResources("test_architecture.json");
        File test_experiment = FileLoaderUtil.loadFromTestResources("test_experiment.json");

        MiSimModel model = new MiSimModel(test_architecture, test_experiment);

        ExportUtils.prepareReportDirectory(null, model);

        FileUtils.forceDeleteOnExit(model.getExperimentMetaData().getReportBaseDirectory().toFile());
    }
}