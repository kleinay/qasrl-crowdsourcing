package qasrl.crowd

import spacro.{Assignment, HIT}

case class ApprovedGenAssignment[SID](hit: HIT[QASRLGenerationPrompt[SID]],
                                      assignment: Assignment[List[VerbQA]])

