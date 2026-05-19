val Manifest = (
  universe = "staged-skill-authoring",
  api =
    "/* THIS IS PRE-IMPORTED, DO NOT REPEAT */ val stagedSkills: stagedskills.StagedSkillWorkspaceAccess = ...",
  predef = "val stagedSkills: stagedskills.StagedSkillWorkspaceAccess = stagedskills.internal.impl"
)
