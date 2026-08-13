package io.vpp.devicesimulator.runtime;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/simulator")
public class SimulatorController {
    private final SimulatorRuntime runtime;

    public SimulatorController(SimulatorRuntime runtime) {
        this.runtime = runtime;
    }

    @GetMapping("/status")
    public SimulatorStatus status() {
        return runtime.status();
    }
}
