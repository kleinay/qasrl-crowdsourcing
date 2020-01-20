package qasrl.crowd

import spacro.{Assignment, HIT}

case class ApprovedGenAssignment[SID](hit: HIT[QANomGenerationPrompt[SID]],
                                      assignment: Assignment[QANomResponse])

