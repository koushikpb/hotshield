# RDS rejects '/', '@', '"' and spaces in the master password.
resource "random_password" "db" {
  length           = 32
  override_special = "-_.~!#%^*"
}

# SecureString on the standard tier is free; a Secrets Manager secret would bill per month.
# The plaintext still sits in Terraform state, which is why *.tfstate is gitignored.
resource "aws_ssm_parameter" "db_password" {
  name  = "/hotshield/db-password"
  type  = "SecureString"
  value = random_password.db.result
}

resource "aws_db_subnet_group" "main" {
  name       = "hotshield"
  subnet_ids = aws_subnet.private[*].id
}

resource "aws_db_parameter_group" "main" {
  name   = "hotshield-pg16"
  family = "postgres16"

  # Static parameter: RDS only loads it at boot. A fresh instance boots with it; changing it later needs a reboot.
  parameter {
    name         = "shared_preload_libraries"
    value        = "pg_stat_statements"
    apply_method = "pending-reboot"
  }
}

resource "aws_db_instance" "main" {
  identifier     = "hotshield"
  engine         = "postgres"
  engine_version = "16"
  instance_class = var.db_instance_class

  allocated_storage = var.db_allocated_storage
  storage_type      = "gp3"

  db_name  = "hotshield"
  username = "hotshield"
  password = random_password.db.result

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  parameter_group_name   = aws_db_parameter_group.main.name
  multi_az               = false
  publicly_accessible    = false

  # Disposable benchmark database: destroy must not leave a snapshot or backups behind, and must not be blocked.
  skip_final_snapshot     = true
  deletion_protection     = false
  backup_retention_period = 0
  apply_immediately       = true
}
