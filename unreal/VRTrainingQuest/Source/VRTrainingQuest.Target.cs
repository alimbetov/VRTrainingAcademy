using UnrealBuildTool;
using System.Collections.Generic;

public class VRTrainingQuestTarget : TargetRules
{
    public VRTrainingQuestTarget(TargetInfo Target) : base(Target)
    {
        Type = TargetType.Game;
        DefaultBuildSettings = BuildSettingsVersion.V5;
        ExtraModuleNames.Add("VRTrainingQuest");
    }
}
