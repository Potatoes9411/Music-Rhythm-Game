# JUnit summary

## core (:core:test): 53/53 passed

| class | test | s | result |
|---|---|---|---|
| AnalysisTest | realisticMusicMatchesMidiGroundTruth() | 0.80 | pass |
| AnalysisTest | cacheReusesByFingerprintVersionAndSettings() | 0.09 | pass |
| AnalysisTest | clickTrackTempoBeatsDownbeatsOnsets() | 0.17 | pass |
| ArchTest | ballisticSolutionIsExactAndDeterministic() | 0.03 | pass |
| ArchTest | cameraDirectorComposition() | 0.23 | pass |
| ArchTest | archSyncAndSeekDeterminism() | 0.05 | pass |
| AudioSessionTest | audioEventsAreRealAudioEventsNotMidi() | 0.51 | pass |
| AudioSessionTest | squareDrivenByAudioAnalysisMeetsSyncAndIsDeterministic() | 0.21 | pass |
| CircleTest | deterministicAnomaliesSeekAndReplayForEveryPreset() | 4.09 | pass |
| CircleTest | containmentHoldsForAllClosedRingPresets() | 1.41 | pass |
| CircleTest | persistentPaintIsIdenticalAfterSeekAndReplay() | 0.47 | pass |
| CircleTest | noTunnelingAtExtremeSpeed() | 0.01 | pass |
| CircleTest | sandboxInputsReplayExactly() | 0.03 | pass |
| CircleTest | elasticRingConservesSpeedAndReflectsAboutNormal() | 0.00 | pass |
| EngineTest | noDuplicateImpactsAcrossPauseAndResume() | 0.02 | pass |
| EngineTest | simulationIsIndependentOfDisplayRate() | 0.07 | pass |
| EngineTest | rendersWithoutCrashAtManyTimes() | 0.04 | pass |
| EngineTest | seekRestoresAndFastForwardsToIdenticalState() | 0.04 | pass |
| EngineTest | plannedSquareMeetsSyncGoals() | 0.08 | pass |
| MidiStructureTest | demoSectionsFoundFromMidiStructure() | 0.00 | pass |
| MidiTest | polyphonyMappingsAreDeterministicAndDistinct() | 0.03 | pass |
| MidiTest | runningStatusAndZeroVelocityNoteOff() | 0.00 | pass |
| MidiTest | rejectsNonMidi() | 0.01 | pass |
| MidiTest | thinningRespectsMinimumGapAndKeepsInformation() | 0.01 | pass |
| MidiTest | demoSongParsesWithSectionsAndDrums() | 0.00 | pass |
| MidiTest | parsesTempoMapAndNotes() | 0.00 | pass |
| PlatformTest | syncSeekAndComposition() | 0.43 | pass |
| PlatformTest | courseIsGeneratedFromEventsLandsExactlyAndDescends() | 0.02 | pass |
| PresetTest | allBuiltInsRoundTripThroughJson() | 0.02 | pass |
| PresetTest | importValidatesAndClamps() | 0.01 | pass |
| ReplayTest | sandboxInputsAreReplayedAndVerified() | 0.02 | pass |
| ReplayTest | compatibilityFlagsDifferentMedia() | 0.00 | pass |
| ReplayTest | exportImportReproducesRunIncludingCustomPreset() | 0.04 | pass |
| RngTest | uniformity() | 0.00 | pass |
| RngTest | sameSeedSameSequenceAndStreamsIndependent() | 0.00 | pass |
| SquareCompositionGauntletTest | heroLargeCourseFillsFrameNoCage() | 0.34 | pass |
| SquareTest | syntheticDenseEventsStillPlan() | 0.06 | pass |
| SquareTest | sameEventsAndSeedGiveSameRouteRegardlessOfChunking() | 0.05 | pass |
| SquareTest | courseIsCleanNoOverlapsNoPassThrough() | 0.08 | pass |
| SquareTest | everyPhysicalEventBecomesAContactExactlyOnTime() | 0.02 | pass |
| SquareTest | routeStaysFramedForPortrait() | 0.01 | pass |
| SynthTest | notesAreInTuneAndSampleBased() | 0.27 | pass |
| SynthTest | wavRoundTrip() | 0.01 | pass |
| SynthTest | parsesGeneralUserBank() | 0.00 | pass |
| SynthTest | demoRenderIsDeterministicFastAndWellLevelled() | 5.71 | pass |
| SynthTest | drumsAndEnvelopesBehave() | 0.01 | pass |
| ViewModeTest | quadAndDuetRunAllSlots() | 0.14 | pass |
| ViewModeTest | journeyUsesEveryMechanicOnOneClockAndSeeksDeterministically() | 0.20 | pass |
| AnalysisProbe | probe() | 0.00 | pass |
| ArchProbe | probe() | 0.00 | pass |
| ArchProbe | worstContact() | 0.00 | pass |
| PlannerProbe | probe() | 0.00 | pass |
| PlatformProbe | probe() | 0.00 | pass |

## app pure logic (:app:test): 5/5 passed

| class | test | s | result |
|---|---|---|---|
| PureLogicTest | snifferNeverTreatsAudioAsMidi() | 0.05 | pass |
| PureLogicTest | clockIsMonotonicAndFollowsTheDevice() | 0.01 | pass |
| PureLogicTest | collisionLayerNotes() | 0.00 | pass |
| PureLogicTest | thermalCapOnlyLowersQuality() | 0.00 | pass |
| PureLogicTest | letterboxAndPts() | 0.01 | pass |

## app on Robolectric (:app:roboTest): 8/8 passed

| class | test | s | result |
|---|---|---|---|
| AppFlowTest | demoSongCreatorWorkflow | 15.84 | pass |
| AppFlowTest | homeScreenLaunches | 0.21 | pass |
| AppFlowTest | importedFilesAreRoutedByContentNotName | 3.00 | pass |
| AppFlowTest | bundledSoundFontIsPackagedAsAsset | 0.42 | pass |
| AppFlowTest | openingAndClosingTheCreatorDoesNotLeakThreads | 1.07 | pass |
| AppFlowTest | sandboxIsCircleOnlyAndRecordsTaps | 0.91 | pass |
| AppFlowTest | landscapeCreatorUsesSidePanel | 4.54 | pass |
| CanvasBackendTest | everyPresetRendersThroughAndroidCanvas | 6.64 | pass |

