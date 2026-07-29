#pragma once

#include "modloader/runtime_state.h"

namespace modloader {

void LogState(RuntimeState state);
void LogFailure(FailureCode failure);
void LogMessage(const char* message);

}
