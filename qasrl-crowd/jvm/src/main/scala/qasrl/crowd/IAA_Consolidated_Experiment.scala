package qasrl.crowd

object IAA_Consolidated_Experiment {
  def split_arb_prompt_by_worker_groups[SID](arbPrompt: QASRLArbitrationPrompt[SID],
                                              workersInGroup: Map[String, List[String]]): List[QASRLArbitrationPrompt[SID]] = {
    // from each arbitration prompt of this many-generators data, generate 2 arbitration prompts
    // by grouping the gen-responses by workers-groups.
    // This way, the geneartion responses of same-grouped workers will get into a single arbitration prompt,
    // while all other gen-responses will get into another.
    val groups : List[List[String]] = workersInGroup.values.toList
    val generator2genResponse : Map[String, QANomResponse] = arbPrompt.genResponses.toMap
    val generators = arbPrompt.generators.toSet
    val generatorPairs = generators.subsets.filter(_.size==2)
    val sameGroupWrkPairOpt: Option[Set[String]] = generatorPairs.find(pair => groups.exists(grp => pair.subsetOf(grp.toSet)))
    // there must exist such a pair when #-groups < #-workers  (pigeon-holes),
    //    e.g. HIT with 4 generators when there are only 3 groups
    if (sameGroupWrkPairOpt.isEmpty)
      // otherwise - just return the prompt as is
      List(arbPrompt)
    else {
      val sameGroupWorkerPair: Set[String] = sameGroupWrkPairOpt.get
      val p1 = QASRLArbitrationPrompt(arbPrompt.genPrompt, sameGroupWorkerPair.toList.map(w => (w, generator2genResponse(w))))
      val otherWorkers = generators -- sameGroupWorkerPair
      val p2 = QASRLArbitrationPrompt(arbPrompt.genPrompt, otherWorkers.toList.map(w => (w, generator2genResponse(w))))
      // return
      List(p1, p2)
    }
  }
}
