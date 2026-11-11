#pragma once

#include "CoreMinimal.h"
#include "Engine/GameInstance.h"
#include "VRTAQuestGameInstance.generated.h"

/**
 * Runtime shell for an offline-capable Quest experience.
 * Bundled level may open offline. Remotely downloaded levels MUST NOT mount
 * until signed release verification + platform-specific mounting are qualified.
 */
UCLASS()
class VRTRAININGQUEST_API UVRTAQuestGameInstance : public UGameInstance
{
    GENERATED_BODY()

public:
    UFUNCTION(BlueprintCallable, Category="VRTA|Lessons")
    bool OpenBundledLessonOffline(const FString& LevelObjectPath);

    UFUNCTION(BlueprintCallable, Category="VRTA|Lessons")
    bool OpenDownloadedLesson(const FString& ReleaseId, const FString& LevelObjectPath);

    UFUNCTION(BlueprintPure, Category="VRTA|Runtime")
    FString GetLocalLessonDirectory() const;

    UFUNCTION(BlueprintPure, Category="VRTA|Runtime")
    FString GetNativeRuntimeVersion() const;

private:
    static bool IsAllowedLevelPath(const FString& Path);
};
