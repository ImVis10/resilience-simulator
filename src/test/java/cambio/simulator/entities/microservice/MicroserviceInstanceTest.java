package cambio.simulator.entities.microservice;

import java.lang.reflect.Field;
import java.util.*;

import cambio.simulator.test.RandomTieredModel;
import cambio.simulator.test.TestUtils;
import co.paralleluniverse.fibers.SuspendExecution;
import desmoj.core.simulator.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MicroserviceInstanceTest {

    @Test
    void allInstancesInStateShutdownCorrectly() {
        RandomTieredModel model = new RandomTieredModel("MSTestModel", 3, 3);
        Experiment exp = TestUtils.getExampleExperiment(model, 300);


        final List<MicroserviceInstance> instanceList = new LinkedList<>();


        ExternalEvent instanceCollection = new ExternalEvent(model, "InstanceCollection", false) {
            @Override
            public void eventRoutine() throws SuspendExecution {
                model.getAllMicroservices().forEach(microservice -> {
                    try {
                        Field f = Microservice.class.getDeclaredField("instancesSet");
                        f.setAccessible(true);
                        instanceList.addAll((Collection<? extends MicroserviceInstance>) f.get(microservice));
                    } catch (IllegalAccessException | NoSuchFieldException e) {
                        e.printStackTrace();
                    }
                });
            }
        };
        instanceCollection.schedule(new TimeInstant(10));

        ExternalEvent shutdown = new ExternalEvent(model, "ShutdownEvent", false) {
            @Override
            public void eventRoutine() throws SuspendExecution {
                model.getAllMicroservices().forEach(microservice -> microservice.scaleToInstancesCount(0));
            }
        };
        shutdown.schedule(new TimeInstant(200));

        exp.start();
        exp.finish();

        instanceList.forEach(instance -> Assertions.assertTrue(
            instance.getState() == InstanceState.SHUTDOWN || instance.getState() == InstanceState.SHUTTING_DOWN));
        instanceList.forEach(instance -> Assertions.assertEquals(0.0, instance.getRelativeWorkDemand()));
    }
}