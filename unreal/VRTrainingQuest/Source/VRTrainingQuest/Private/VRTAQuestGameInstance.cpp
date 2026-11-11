#include "VRTAQuestGameInstance.h"

#include "Kismet/GameplayStatics.h"
#include "Misc/Paths.h"

DEFINE_LOG_CATEGORY_STATIC(LogVRTAQuestRuntime, Log, All);

bool UVRTAQuestGameInstance::IsAllowedLevelPath(const FString& Path)
{
    return Path.StartsWith(TEXT("/Game/Lessons/"))
        && !Path.Contains(TEXT(".."))
        && !Path.Contains(TEXT("\\"))
        && Path.Len() <= 240;
}

bool UVRTAQuestGameInstance::OpenBundledLessonOffline(const FString& LevelObjectPath)
{
    if (!IsAllowedLevelPath(LevelObjectPath))
    {
        UE_LOG(LogVRTAQuestRuntime, Warning, TEXT("Bundled lesson path rejected"));
        return false;
    }
    // OpenLevel accepts an Unreal long package path, not a filesystem path.
    UGameplayStatics::OpenLevel(this, FName(*LevelObjectPath));
    return true; // Dispatch only. Existence and successful load require native UAT.
}

bool UVRTAQuestGameInstance::OpenDownloadedLesson(
    const FString& ReleaseId, const FString& LevelObjectPath)
{
    // SECURITY: Do not treat an arbitrary local JSON marker or Blueprint call as
    // proof of signed manifest, artifact hashes, license or mounted containers.
    // A future VerifiedReleaseRegistry + mount adapter must enforce all gates.
    UE_LOG(LogVRTAQuestRuntime, Warning,
        TEXT("Downloaded lesson launch blocked until verified mount adapter is implemented"));
    return false;
}

FString UVRTAQuestGameInstance::GetLocalLessonDirectory() const
{
    return FPaths::Combine(FPaths::ProjectPersistentDownloadDir(), TEXT("VRTA"), TEXT("releases"));
}

FString UVRTAQuestGameInstance::GetNativeRuntimeVersion() const
{
    return TEXT("0.1.0-foundation");
}
