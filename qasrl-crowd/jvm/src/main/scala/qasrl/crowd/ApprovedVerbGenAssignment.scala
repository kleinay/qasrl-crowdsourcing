package qasrl.crowd

import spacro.{Assignment, HIT}

case class ApprovedVerbGenAssignment[SID](hit: HIT[QASRLGenerationPrompt[SID]],
                                          assignment: Assignment[List[VerbQA]])

