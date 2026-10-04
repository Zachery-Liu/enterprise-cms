package com.zachery.cms.modules.content.publication;

import jakarta.validation.constraints.*;

/** Target state and operator are never client input. Action is selected by the application entry point. */
public record TransitionCommand(@NotNull @PositiveOrZero Integer version, @Size(max=500) String comment) { }
