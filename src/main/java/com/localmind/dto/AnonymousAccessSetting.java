package com.localmind.dto;

import jakarta.validation.constraints.NotNull;

public record AnonymousAccessSetting(@NotNull Boolean allowed) {
}
