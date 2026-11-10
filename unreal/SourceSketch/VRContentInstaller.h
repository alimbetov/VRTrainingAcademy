#pragma once
// Interface design sketch only; integrate into generated Unreal C++ project.
#include "CoreMinimal.h"

struct FVRContentRelease {
    FString ReleaseId;
    FString ModuleId;
    FString RuntimeBuildId;
    FString EntryLevel;
    TArray<FString> ArtifactNames;
};

// Native interface isolates bundled content from downloaded cooked chunks.
// Installation success must follow signature, SHA-256, compatibility checks.
class IVRContentInstaller {
public:
    virtual ~IVRContentInstaller() = default;
    virtual void Install(
        const FVRContentRelease& Release,
        TFunction<void(bool)> Completed) = 0;
    virtual bool IsInstalled(const FString& ReleaseId) const = 0;
};
