# Unreal Experience Runtime — R0 design stub

This directory does **not** contain a generated Unreal project yet. Create a UE5 C++ project named `VRTrainingAcademy` in Unreal Editor, then integrate the classes below with the generated module/API macro.

Target architecture:
- `UVRSessionSubsystem`: teacher-approved device sessions (R1).
- `UVRCatalogSubsystem`: HTTP catalog DTO and cached snapshot.
- `UVRContentSubsystem`: signed release download, verification, staging, mount (R2/R3).
- `UVRLearningSubsystem`: lesson runtime and offline progress.
- `BPI_Interactable`: Blueprint interaction contract.
- `WBP_KnowledgeHub`: user-facing world discovery UI.

See `SourceSketch/VRContentInstaller.h` for the installer boundary. No dynamic Quest pak support is claimed until verified on device.
