# force_delete lets destroy remove the repo with images still in it; otherwise destroy fails and storage keeps billing.
# Immutable tags: a tag is a git SHA and always means the same build, so an experiment's recorded image_tag
# identifies exactly the code that ran.
resource "aws_ecr_repository" "app" {
  name                 = "hotshield"
  image_tag_mutability = "IMMUTABLE"
  force_delete         = true
}
