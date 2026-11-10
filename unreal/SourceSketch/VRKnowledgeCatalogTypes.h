#pragma once
// Unreal C++ contract sketch; NOT compiled until the UE project/module is generated.
// Data structures intentionally mirror contracts/catalog.snapshot.v1.example.json
#include "CoreMinimal.h"

struct FVRWorld {
    FString Id;
    FString TitleKey;
    int32 SortOrder = 0;
};
struct FVRArea {
    FString Id;
    FString WorldId;
    FString TitleKey;
    int32 SortOrder = 0;
};
struct FVRSubject {
    FString Id;
    FString AreaId;
    FString TitleKey;
    int32 SortOrder = 0;
};
struct FVRLesson {
    FString Id;
    FString TitleKey;
    FString ExperienceId;
    bool bIsFree = false; // UI hint ONLY, never an entitlement decision
};
struct FVRLessonPlacement {
    FString WorldId;
    FString AreaId;
    FString SubjectId;
    FString LessonId;
    int32 SortOrder = 0;
};
struct FVRExperience {
    FString Id;
    FString EntryLevel;
    TArray<FString> RequiredModuleIds;
};

// Client navigation service accepts an already validated immutable snapshot.
// No network or download code in the navigation boundary.
class IVRKnowledgeNavigator {
public:
    virtual ~IVRKnowledgeNavigator() = default;
    virtual TArray<FVRWorld> GetWorlds() const = 0;
    virtual TArray<FVRArea> GetAreas(const FString& WorldId) const = 0;
    virtual TArray<FVRSubject> GetSubjects(const FString& AreaId) const = 0;
    virtual TArray<FVRLesson> GetLessons(const FString& WorldId,
                                         const FString& AreaId,
                                         const FString& SubjectId) const = 0;
};
