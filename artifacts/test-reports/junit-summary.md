# JUnit summary

## core (:core:test): 52/52 passed

| class | test | s | result |
|---|---|---|---|
| AnalysisTest | realisticMusicMatchesMidiGroundTruth() | 0.64 | pass |
| AnalysisTest | cacheReusesByFingerprintVersionAndSettings() | 0.07 | pass |
| AnalysisTest | clickTrackTempoBeatsDownbeatsOnsets() | 0.17 | pass |
| ArchTest | ballisticSolutionIsExactAndDeterministic() | 0.04 | pass |
| ArchTest | cameraDirectorComposition() | 0.16 | pass |
| ArchTest | archSyncAndSeekDeterminism() | 0.03 | pass |
| AudioSessionTest | audioEventsAreRealAudioEventsNotMidi() | 0.39 | pass |
| AudioSessionTest | squareDrivenByAudioAnalysisMeetsSyncAndIsDeterministic() | 0.17 | pass |
| CircleTest | deterministicAnomaliesSeekAndReplayForEveryPreset() | 3.34 | pass |
| CircleTest | containmentHoldsForAllClosedRingPresets() | 0.80 | pass |
| CircleTest | noTunnelingAtExtremeSpeed() | 0.01 | pass |
| CircleTest | sandboxInputsReplayExactly() | 0.03 | pass |
| CircleTest | elasticRingConservesSpeedAndReflectsAboutNormal() | 0.00 | pass |
| EngineTest | noDuplicateImpactsAcrossPauseAndResume() | 0.02 | pass |
| EngineTest | simulationIsIndependentOfDisplayRate() | 0.06 | pass |
| EngineTest | rendersWithoutCrashAtManyTimes() | 0.04 | pass |
| EngineTest | seekRestoresAndFastForwardsToIdenticalState() | 0.03 | pass |
| EngineTest | plannedSquareMeetsSyncGoals() | 0.05 | pass |
| MidiStructureTest | demoSectionsFoundFromMidiStructure() | 0.00 | pass |
| MidiTest | polyphonyMappingsAreDeterministicAndDistinct() | 0.02 | pass |
| MidiTest | runningStatusAndZeroVelocityNoteOff() | 0.00 | pass |
| MidiTest | rejectsNonMidi() | 0.01 | pass |
| MidiTest | thinningRespectsMinimumGapAndKeepsInformation() | 0.01 | pass |
| MidiTest | demoSongParsesWithSectionsAndDrums() | 0.00 | pass |
| MidiTest | parsesTempoMapAndNotes() | 0.00 | pass |
| PlatformTest | syncSeekAndComposition() | 0.24 | pass |
| PlatformTest | courseIsGeneratedFromEventsLandsExactlyAndDescends() | 0.01 | pass |
| PresetTest | allBuiltInsRoundTripThroughJson() | 0.02 | pass |
| PresetTest | importValidatesAndClamps() | 0.01 | pass |
| ReplayTest | sandboxInputsAreReplayedAndVerified() | 0.02 | pass |
| ReplayTest | compatibilityFlagsDifferentMedia() | 0.00 | pass |
| ReplayTest | exportImportReproducesRunIncludingCustomPreset() | 0.03 | pass |
| RngTest | uniformity() | 0.00 | pass |
| RngTest | sameSeedSameSequenceAndStreamsIndependent() | 0.00 | pass |
| SquareCompositionGauntletTest | heroLargeCourseFillsFrameNoCage() | 0.17 | pass |
| SquareTest | syntheticDenseEventsStillPlan() | 0.06 | pass |
| SquareTest | sameEventsAndSeedGiveSameRouteRegardlessOfChunking() | 0.04 | pass |
| SquareTest | courseIsCleanNoOverlapsNoPassThrough() | 0.05 | pass |
| SquareTest | everyPhysicalEventBecomesAContactExactlyOnTime() | 0.01 | pass |
| SquareTest | routeStaysFramedForPortrait() | 0.00 | pass |
| SynthTest | notesAreInTuneAndSampleBased() | 0.22 | pass |
| SynthTest | wavRoundTrip() | 0.00 | pass |
| SynthTest | parsesGeneralUserBank() | 0.00 | pass |
| SynthTest | demoRenderIsDeterministicFastAndWellLevelled() | 4.74 | pass |
| SynthTest | drumsAndEnvelopesBehave() | 0.00 | pass |
| ViewModeTest | quadAndDuetRunAllSlots() | 0.10 | pass |
| ViewModeTest | journeyUsesEveryMechanicOnOneClockAndSeeksDeterministically() | 0.12 | pass |
| AnalysisProbe | probe() | 0.00 | pass |
| ArchProbe | probe() | 0.00 | pass |
| ArchProbe | worstContact() | 0.00 | pass |
| PlannerProbe | probe() | 0.00 | pass |
| PlatformProbe | probe() | 0.00 | pass |

## app pure logic (:app:test): 5/5 passed

| class | test | s | result |
|---|---|---|---|
| PureLogicTest | snifferNeverTreatsAudioAsMidi() | 0.06 | pass |
| PureLogicTest | clockIsMonotonicAndFollowsTheDevice() | 0.01 | pass |
| PureLogicTest | collisionLayerNotes() | 0.00 | pass |
| PureLogicTest | thermalCapOnlyLowersQuality() | 0.01 | pass |
| PureLogicTest | letterboxAndPts() | 0.02 | pass |

## app on Robolectric (:app:roboTest): 7/7 passed

| class | test | s | result |
|---|---|---|---|
| AppFlowTest | demoSongCreatorWorkflow | 12.54 | pass |
| AppFlowTest | homeScreenLaunches | 0.22 | pass |
| AppFlowTest | importedFilesAreRoutedByContentNotName | 2.49 | pass |
| AppFlowTest | bundledSoundFontIsPackagedAsAsset | 0.37 | pass |
| AppFlowTest | sandboxIsCircleOnlyAndRecordsTaps | 1.01 | pass |
| AppFlowTest | landscapeCreatorUsesSidePanel | 3.26 | pass |
| CanvasBackendTest | everyPresetRendersThroughAndroidCanvas | 4.76 | pass |

