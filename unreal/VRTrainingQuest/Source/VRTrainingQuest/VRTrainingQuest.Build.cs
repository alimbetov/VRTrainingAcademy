using UnrealBuildTool;

public class VRTrainingQuest : ModuleRules
{
    public VRTrainingQuest(ReadOnlyTargetRules Target) : base(Target)
    {
        PCHUsage = PCHUsageMode.UseExplicitOrSharedPCHs;
        PublicDependencyModuleNames.AddRange(new[] { "Core", "CoreUObject", "Engine" });
        PrivateDependencyModuleNames.AddRange(new[] {
            "Json", "JsonUtilities", "Projects", "ChunkDownloader"
        });
    }
}
